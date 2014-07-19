/*
 * Copyright 1999-2004 Carnegie Mellon University.  
 * Portions Copyright 2004 Sun Microsystems, Inc.  
 * Portions Copyright 2004 Mitsubishi Electric Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */
package edu.cmu.sphinx.linguist.acoustic.tiedstate;

import edu.cmu.sphinx.linguist.acoustic.*;
import static edu.cmu.sphinx.linguist.acoustic.tiedstate.Pool.Feature.*;
import edu.cmu.sphinx.util.ExtendedStreamTokenizer;
import edu.cmu.sphinx.util.LogMath;
import edu.cmu.sphinx.util.TimerPool;
import edu.cmu.sphinx.util.Utilities;
import edu.cmu.sphinx.util.props.*;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads a tied-state acoustic model generated by the Sphinx-3 trainer.
 * <p>
 * The acoustic model is stored as a directory specified by a URL. The
 * dictionary and language model files are not required to be in the package.
 * You can specify their locations separately.
 * </p>
 * <p>
 * Configuration file should set mandatory property of component:
 * <ul>
 * <li><b>location</b> - this specifies the directory where the actual model
 * data files are. You can use <b>resource:</b> prefix to refer to files packed
 * inside jar or any other URI scheme.
 * </ul>
 * The actual model data files are named "mdef", "means", "variances",
 * "transition_matrices", "mixture_weights".
 * </p>
 * <p>
 * If model has some layout that is different default generated by SphinxTrain,
 * you may specify additional properties like <b>dataLocation</b> to set the
 * path to the binary files, <b>mdef</b> to set the path to the model definition
 * file.
 * </p>
 * <p>
 * As an example, lets look at the Wall Street Journal acoustic model JAR file,
 * which is located at the <code>sphinx4/lib</code> directory. If you run
 * <code>"jar tf lib/WSJ_8gau_13dCep_16k_40mel_130Hz_6800Hz.jar"</code>, you
 * will find that its internal structure looks roughly like:
 * <p/>
 * 
 * <pre>
 * WSJ_8gau_13dCep_16k_40mel_130Hz_6800Hz.jar
 *   |
 *   +- license.terms
 *   +- cd_continuous_8gau
 *   |   |
 *   |   +- means
 *   |   +- variances
 *   |   +- mixture_weights
 *   |   +- transition_matrices
 *   |
 *   +- dict
 *   |   |
 *   |   +- alpha.dict
 *   |   +- cmudict.0.6d
 *   |   +- digits.dict
 *   |   +- fillerdict
 *   |
 *   +- etc
 *       |
 *       +- WSJ_clean_13dCep_16k_40mel_130Hz_6800Hz.4000.mdef
 *       +- WSJ_clean_13dCep_16k_40mel_130Hz_6800Hz.ci.mdef
 *       +- variables.def
 * </pre>
 * <p/>
 * <p>
 * So the configuration to load this model should look like
 * 
 * <pre>
 *  &lt;component name="wsjLoader" type="edu.cmu.sphinx.linguist.acoustic.tiedstate.Sphinx3Loader"&gt;
 *     &lt;property name="logMath" value="logMath"/&gt;
 *        &lt;property name="unitManager" value="unitManager"/&gt;
 *        &lt;property name="location" value="resource:/WSJ_8gau_13dCep_16k_40mel_130Hz_6800Hz"/&gt;
 *        &lt;property name="modelDefinition" value="etc/WSJ_clean_13dCep_16k_40mel_130Hz_6800Hz.4000.mdef"/&gt;
 *        &lt;property name="dataLocation" value="cd_continuous_8gau/"/&gt;
 *    &lt;/component&gt;
 * </pre>
 * 
 * </p>
 * <p>
 * For more details on using SphinxTrain models in sphinx4, see <a
 * href="../../../../../../../doc/UsingSphinxTrainModels.html">documentation</a>
 * </p>
 */

public class Sphinx3Loader implements Loader {

    /**
     * The unit manager
     */
    @S4Component(type = UnitManager.class)
    public final static String PROP_UNIT_MANAGER = "unitManager";

    /**
     * The root location of the model directory structure
     */
    @S4String(mandatory = true)
    public final static String PROP_LOCATION = "location";

    /**
     * The name of the model definition file (contains the HMM data)
     */
    @S4String(mandatory = false, defaultValue = "mdef")
    public final static String PROP_MODEL = "modelDefinition";

    /**
     * Subfolder where the acoustic model can be found
     */
    @S4String(mandatory = false, defaultValue = "")
    public final static String PROP_DATA_LOCATION = "dataLocation";

    /**
     * The property specifying whether context-dependent units should be used.
     */
    @S4Boolean(defaultValue = true)
    public final static String PROP_USE_CD_UNITS = "useCDUnits";

    /**
     * Mixture component score floor.
     */
    @S4Double(defaultValue = 0.0f)
    public final static String PROP_MC_FLOOR = "MixtureComponentScoreFloor";

    /**
     * Variance floor.
     */
    @S4Double(defaultValue = 0.0001f)
    public final static String PROP_VARIANCE_FLOOR = "varianceFloor";

    /**
     * Mixture weight floor.
     */
    @S4Double(defaultValue = 1e-7f)
    public final static String PROP_MW_FLOOR = "mixtureWeightFloor";

    protected final static String FILLER = "filler";
    protected final static String SILENCE_CIPHONE = "SIL";
    protected final static int BYTE_ORDER_MAGIC = 0x11223344;

    /**
     * Supports this version of the acoustic model
     */
    public final static String MODEL_VERSION = "0.3";

    private final static int CONTEXT_SIZE = 1;
    protected Properties modelProps;
    protected Pool<float[]> meansPool;
    protected Pool<float[]> variancePool;
    protected Pool<float[][]> transitionsPool;
    protected Pool<float[]> mixtureWeightsPool;

    private Pool<float[][]> meanTransformationMatrixPool;
    private Pool<float[]> meanTransformationVectorPool;
    private Pool<float[][]> varianceTransformationMatrixPool;
    private Pool<float[]> varianceTransformationVectorPool;

    protected float[][] transformMatrix;
    protected Pool<Senone> senonePool;

    private Map<String, Unit> contextIndependentUnits;
    private HMMManager hmmManager;
    protected LogMath logMath;
    private UnitManager unitManager;
    private boolean swap;

    private final static String DENSITY_FILE_VERSION = "1.0";
    private final static String MIXW_FILE_VERSION = "1.0";
    private final static String TMAT_FILE_VERSION = "1.0";
    private final static String TRANSFORM_FILE_VERSION = "0.1";
    // --------------------------------------
    // Configuration variables
    // --------------------------------------
    protected Logger logger;
    private URL location;
    protected String model;
    protected String dataLocation;
    protected float distFloor;
    protected float mixtureWeightFloor;
    protected float varianceFloor;
    protected boolean useCDUnits;
    private boolean loaded;

    public Sphinx3Loader(URL location, String model, String dataLocation,
            UnitManager unitManager, float distFloor, float mixtureWeightFloor,
            float varianceFloor, boolean useCDUnits) {

        init(location, model, dataLocation, unitManager, distFloor,
                mixtureWeightFloor, varianceFloor, useCDUnits,
                Logger.getLogger(getClass().getName()));
    }

    public Sphinx3Loader(String location, String model, String dataLocation,
            UnitManager unitManager, float distFloor, float mixtureWeightFloor,
            float varianceFloor, boolean useCDUnits)
            throws MalformedURLException, ClassNotFoundException {

        init(ConfigurationManagerUtils.resourceToURL(location), model,
                dataLocation, unitManager, distFloor, mixtureWeightFloor,
                varianceFloor, useCDUnits,
                Logger.getLogger(getClass().getName()));
    }

    protected void init(URL location, String model, String dataLocatoin,
            UnitManager unitManager, float distFloor, float mixtureWeightFloor,
            float varianceFloor, boolean useCDUnits, Logger logger) {
        logMath = LogMath.getLogMath();
        this.location = location;
        this.logger = logger;
        this.model = model;
        this.dataLocation = dataLocatoin;
        this.unitManager = unitManager;
        this.distFloor = distFloor;
        this.mixtureWeightFloor = mixtureWeightFloor;
        this.varianceFloor = varianceFloor;
        this.useCDUnits = useCDUnits;
    }

    public Sphinx3Loader() {

    }

    public void newProperties(PropertySheet ps) throws PropertyException {

        init(ConfigurationManagerUtils.getResource(PROP_LOCATION, ps),
                ps.getString(PROP_MODEL), ps.getString(PROP_DATA_LOCATION),
                (UnitManager) ps.getComponent(PROP_UNIT_MANAGER),
                ps.getFloat(PROP_MC_FLOOR), ps.getFloat(PROP_MW_FLOOR),
                ps.getFloat(PROP_VARIANCE_FLOOR),
                ps.getBoolean(PROP_USE_CD_UNITS), ps.getLogger());
    }

    // This function is a bit different from the
    // ConfigurationManagerUtils.getResource
    // for compatibility reasons. By default it looks for the resources, not
    // for the files.
    protected InputStream getDataStream(String path) throws IOException,
            URISyntaxException {
        return new URL(location.toURI().toString() + "/" + path).openStream();
    }

    public void load() throws IOException {
        if (!loaded) {
            TimerPool.getTimer(this, "Load AM").start();

            hmmManager = new HMMManager();
            contextIndependentUnits = new LinkedHashMap<String, Unit>();

            // dummy pools for these elements
            meanTransformationMatrixPool = null;
            meanTransformationVectorPool = null;
            varianceTransformationMatrixPool = null;
            varianceTransformationVectorPool = null;
            transformMatrix = null;

            // do the actual acoustic model loading
            try {
                loadModelFiles(model);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            // done
            loaded = true;
            TimerPool.getTimer(this, "Load AM").stop();
        }
    }

    /**
     * Return the HmmManager.
     * 
     * @return the hmmManager
     */
    protected HMMManager getHmmManager() {
        return hmmManager;
    }

    /**
     * Return the MatrixPool.
     * 
     * @return the matrixPool
     */
    protected Pool<float[][]> getMatrixPool() {
        return transitionsPool;
    }

    /**
     * Return the MixtureWeightsPool.
     * 
     * @return the mixtureWeightsPool
     */
    protected Pool<float[]> getMixtureWeightsPool() {
        return mixtureWeightsPool;
    }

    /**
     * Loads the AcousticModel from a directory in the file system.
     * 
     * @param modelDef
     *            the name of the acoustic modelDef; if null we just load from
     *            the default location
     * @throws java.io.IOException
     */
    protected void loadModelFiles(String modelDef) throws IOException,
            URISyntaxException {

        logger.config("Loading Sphinx3 acoustic model: " + modelDef);
        logger.config("    modelName: " + this.model);
        logger.config("    dataLocation   : " + dataLocation);

        meansPool = loadDensityFile(dataLocation + "means", -Float.MAX_VALUE);
        variancePool = loadDensityFile(dataLocation + "variances",
                varianceFloor);
        mixtureWeightsPool = loadMixtureWeights(dataLocation
                + "mixture_weights", mixtureWeightFloor);
        transitionsPool = loadTransitionMatrices(dataLocation
                + "transition_matrices");
        transformMatrix = loadTransformMatrix(dataLocation
                + "feature_transform");

        senonePool = createSenonePool(distFloor, varianceFloor);

        // load the HMM modelDef file
        InputStream modelStream = getDataStream(this.model);
        if (modelStream == null) {
            throw new IOException("can't find modelDef " + this.model);
        }
        loadHMMPool(useCDUnits, modelStream, this.model);

        modelProps = loadModelProps(dataLocation + "feat.params");
    }

    public Map<String, Unit> getContextIndependentUnits() {
        return contextIndependentUnits;
    }

    /**
     * Creates the senone pool from the rest of the pools.
     * 
     * @param distFloor
     *            the lowest allowed score
     * @param varianceFloor
     *            the lowest allowed variance
     * @return the senone pool
     */
    protected Pool<Senone> createSenonePool(float distFloor, float varianceFloor) {
        Pool<Senone> pool = new Pool<Senone>("senones");
        int numMixtureWeights = mixtureWeightsPool.size();

        int numMeans = meansPool.size();
        int numVariances = variancePool.size();
        int numGaussiansPerSenone = mixtureWeightsPool.getFeature(
                NUM_GAUSSIANS_PER_STATE, 0);
        int numSenones = mixtureWeightsPool.getFeature(NUM_SENONES, 0);
        int numStreams = mixtureWeightsPool.getFeature(NUM_STREAMS, 0);
        int whichGaussian = 0;

        logger.fine("Senones " + numSenones);
        logger.fine("Gaussians Per Senone " + numGaussiansPerSenone);
        logger.fine("MixtureWeights " + numMixtureWeights);
        logger.fine("Means " + numMeans);
        logger.fine("Variances " + numVariances);

        assert numGaussiansPerSenone > 0;
        assert numMixtureWeights == numSenones;
        assert numVariances == numSenones * numGaussiansPerSenone;
        assert numMeans == numSenones * numGaussiansPerSenone;

        float[][] meansTransformationMatrix = meanTransformationMatrixPool == null ? null
                : meanTransformationMatrixPool.get(0);
        float[] meansTransformationVector = meanTransformationVectorPool == null ? null
                : meanTransformationVectorPool.get(0);
        float[][] varianceTransformationMatrix = varianceTransformationMatrixPool == null ? null
                : varianceTransformationMatrixPool.get(0);
        float[] varianceTransformationVector = varianceTransformationVectorPool == null ? null
                : varianceTransformationVectorPool.get(0);

        for (int i = 0; i < numSenones; i++) {
            MixtureComponent[] mixtureComponents = new MixtureComponent[numGaussiansPerSenone
                    * numStreams];
            for (int j = 0; j < numGaussiansPerSenone; j++) {
                mixtureComponents[j] = new MixtureComponent(
                        meansPool.get(whichGaussian),
                        meansTransformationMatrix, meansTransformationVector,
                        variancePool.get(whichGaussian),
                        varianceTransformationMatrix,
                        varianceTransformationVector, distFloor, varianceFloor);

                whichGaussian++;
            }

            Senone senone = new GaussianMixture(mixtureWeightsPool.get(i),
                    mixtureComponents, i);
            pool.put(i, senone);
        }
        return pool;
    }

    /**
     * Loads the sphinx3 density file, a set of density arrays are created and
     * placed in the given pool.
     * 
     * @param path
     *            the name of the data
     * @param floor
     *            the minimum density allowed
     * @return a pool of loaded densities
     * @throws FileNotFoundException
     *             if a file cannot be found
     * @throws IOException
     *             if an error occurs while loading the data
     */
    public Pool<float[]> loadDensityFile(String path, float floor)
            throws IOException, URISyntaxException {
        Properties props = new Properties();
        int blockSize = 0;

        DataInputStream dis = readS3BinaryHeader(path, props);

        String version = props.getProperty("version");

        if (version == null || !version.equals(DENSITY_FILE_VERSION)) {
            throw new IOException("Unsupported version in " + path);
        }

        String checksum = props.getProperty("chksum0");
        boolean doCheckSum = (checksum != null && checksum.equals("yes"));
        resetChecksum();

        int numStates = readInt(dis);
        int numStreams = readInt(dis);
        int numGaussiansPerState = readInt(dis);

        int[] vectorLength = new int[numStreams];
        for (int i = 0; i < numStreams; i++) {
            vectorLength[i] = readInt(dis);
        }

        int rawLength = readInt(dis);

        logger.fine("Number of states " + numStates);
        logger.fine("Number of streams " + numStreams);
        logger.fine("Number of gaussians per state " + numGaussiansPerState);
        logger.fine("Vector length " + vectorLength.length);
        logger.fine("Raw length " + rawLength);

        for (int i = 0; i < numStreams; i++) {
            blockSize += vectorLength[i];
        }

        assert rawLength == numGaussiansPerState * blockSize * numStates;

        Pool<float[]> pool = new Pool<float[]>(path);
        pool.setFeature(NUM_SENONES, numStates);
        pool.setFeature(NUM_STREAMS, numStreams);
        pool.setFeature(NUM_GAUSSIANS_PER_STATE, numGaussiansPerState);

        for (int i = 0; i < numStates; i++) {
            for (int j = 0; j < numStreams; j++) {
                for (int k = 0; k < numGaussiansPerState; k++) {
                    float[] density = readFloatArray(dis, vectorLength[j]);
                    Utilities.floorData(density, floor);
                    pool.put(i * numStreams * numGaussiansPerState + j
                            * numGaussiansPerState + k, density);
                }
            }
        }

        validateChecksum(dis, doCheckSum);

        dis.close();
        return pool;
    }

    /**
     * Reads the S3 binary header from the given location + path. Adds header
     * information to the given set of properties.
     * 
     * @param path
     *            the name of the file
     * @param props
     *            the properties
     * @return the input stream positioned after the header
     * @throws IOException
     *             on error
     */
    public DataInputStream readS3BinaryHeader(String path, Properties props)
            throws IOException, URISyntaxException {

        InputStream inputStream = getDataStream(path);

        if (inputStream == null) {
            throw new IOException("Can't open " + path);
        }
        DataInputStream dis = new DataInputStream(new BufferedInputStream(
                inputStream));
        String id = readWord(dis);
        if (!id.equals("s3")) {
            throw new IOException("Not proper s3 binary file " + path);
        }
        String name;
        while ((name = readWord(dis)) != null) {
            if (!name.equals("endhdr")) {
                String value = readWord(dis);
                props.setProperty(name, value);
            } else {
                break;
            }
        }
        int byteOrderMagic = dis.readInt();
        if (byteOrderMagic == BYTE_ORDER_MAGIC) {
            logger.fine("Not swapping " + path);
            swap = false;
        } else if (Utilities.swapInteger(byteOrderMagic) == BYTE_ORDER_MAGIC) {
            logger.fine("Swapping  " + path);
            swap = true;
        } else {
            throw new IOException("Corrupted S3 file " + path);
        }
        return dis;
    }

    /**
     * Reads the next word (text separated by whitespace) from the given stream.
     * 
     * @param dis
     *            the input stream
     * @return the next word
     * @throws IOException
     *             on error
     */
    String readWord(DataInputStream dis) throws IOException {
        StringBuilder sb = new StringBuilder();
        char c;
        // skip leading whitespace
        do {
            c = readChar(dis);
        } while (Character.isWhitespace(c));
        // read the word
        do {
            sb.append(c);
            c = readChar(dis);
        } while (!Character.isWhitespace(c));
        return sb.toString();
    }

    /**
     * Reads a single char from the stream.
     * 
     * @param dis
     *            the stream to read
     * @return the next character on the stream
     * @throws IOException
     *             if an error occurs
     */
    private char readChar(DataInputStream dis) throws IOException {
        return (char) dis.readByte();
    }

    /* Stores checksum during loading */
    private long calculatedCheckSum = 0;

    /**
     * Resets the checksum before loading a new chunk of data
     */
    private void resetChecksum() {
        calculatedCheckSum = 0;
    }

    /**
     * Validates checksum in the stream
     * 
     * @param dis
     *            input stream
     * @param doCheckSum
     *            validates
     * @throws IOException
     *             on error
     **/
    private void validateChecksum(DataInputStream dis, boolean doCheckSum)
            throws IOException {
        if (!doCheckSum)
            return;
        int oldCheckSum = (int) calculatedCheckSum;
        int checkSum = readInt(dis);
        if (checkSum != oldCheckSum) {
            throw new IOException("Invalid checksum "
                    + Long.toHexString(calculatedCheckSum) + " must be "
                    + Integer.toHexString(checkSum));
        }
    }

    /**
     * Read an integer from the input stream, byte-swapping as necessary.
     * 
     * @param dis
     *            the input stream
     * @return an integer value
     * @throws IOException
     *             on error
     */
    public int readInt(DataInputStream dis) throws IOException {
        int val;
        if (swap) {
            val = Utilities.readLittleEndianInt(dis);
        } else {
            val = dis.readInt();
        }
        calculatedCheckSum = ((calculatedCheckSum << 20 | calculatedCheckSum >> 12) + val) & 0xFFFFFFFFL;
        return val;
    }

    /**
     * Read a float from the input stream, byte-swapping as necessary.
     * 
     * @param dis
     *            the input stream
     * @return a floating pint value
     * @throws IOException
     *             on error
     */
    public float readFloat(DataInputStream dis) throws IOException {
        int val;
        if (swap) {
            val = Utilities.readLittleEndianInt(dis);
        } else {
            val = dis.readInt();
        }
        calculatedCheckSum = ((calculatedCheckSum << 20 | calculatedCheckSum >> 12) + val) & 0xFFFFFFFFL;
        return Float.intBitsToFloat(val);
    }

    /**
     * Reads the given number of floats from the stream and returns them in an
     * array of floats.
     * 
     * @param dis
     *            the stream to read data from
     * @param size
     *            the number of floats to read
     * @return an array of size float elements
     * @throws IOException
     *             if an exception occurs
     */
    public float[] readFloatArray(DataInputStream dis, int size)
            throws IOException {
        float[] data = new float[size];
        for (int i = 0; i < size; i++) {
            data[i] = readFloat(dis);
        }
        return data;
    }

    /**
     * Loads the sphinx3 density file, a set of density arrays are created and
     * placed in the given pool.
     * 
     * @param useCDUnits
     *            if true, loads also the context dependent units
     * @param inputStream
     *            the open input stream to use
     * @param path
     *            the path to a density file
     * @throws FileNotFoundException
     *             if a file cannot be found
     * @throws IOException
     *             if an error occurs while loading the data
     */
    protected void loadHMMPool(boolean useCDUnits, InputStream inputStream,
            String path) throws IOException {
        ExtendedStreamTokenizer est = new ExtendedStreamTokenizer(inputStream,
                '#', false);

        logger.fine("Loading HMM file from: " + path);

        est.expectString(MODEL_VERSION);

        int numBase = est.getInt("numBase");
        est.expectString("n_base");

        int numTri = est.getInt("numTri");
        est.expectString("n_tri");

        int numStateMap = est.getInt("numStateMap");
        est.expectString("n_state_map");

        int numTiedState = est.getInt("numTiedState");
        est.expectString("n_tied_state");

        int numContextIndependentTiedState = est
                .getInt("numContextIndependentTiedState");
        est.expectString("n_tied_ci_state");

        int numTiedTransitionMatrices = est.getInt("numTiedTransitionMatrices");
        est.expectString("n_tied_tmat");

        int numStatePerHMM = numStateMap / (numTri + numBase);

        assert numTiedState == mixtureWeightsPool.getFeature(NUM_SENONES, 0);
        assert numTiedTransitionMatrices == transitionsPool.size();

        // Load the base phones
        for (int i = 0; i < numBase; i++) {
            String name = est.getString();
            String left = est.getString();
            String right = est.getString();
            String position = est.getString();
            String attribute = est.getString();
            int tmat = est.getInt("tmat");

            int[] stid = new int[numStatePerHMM - 1];

            for (int j = 0; j < numStatePerHMM - 1; j++) {
                stid[j] = est.getInt("j");
                assert stid[j] >= 0 && stid[j] < numContextIndependentTiedState;
            }
            est.expectString("N");

            assert left.equals("-");
            assert right.equals("-");
            assert position.equals("-");
            assert tmat < numTiedTransitionMatrices;

            Unit unit = unitManager.getUnit(name, attribute.equals(FILLER));
            contextIndependentUnits.put(unit.getName(), unit);

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("Loaded " + unit);
            }

            // The first filler
            if (unit.isFiller() && unit.getName().equals(SILENCE_CIPHONE)) {
                unit = UnitManager.SILENCE;
            }

            float[][] transitionMatrix = transitionsPool.get(tmat);
            SenoneSequence ss = getSenoneSequence(stid);

            HMM hmm = new SenoneHMM(unit, ss, transitionMatrix,
                    HMMPosition.lookup(position));
            hmmManager.put(hmm);
        }

        if (hmmManager.get(HMMPosition.UNDEFINED, UnitManager.SILENCE) == null) {
            throw new IOException("Could not find SIL unit in acoustic model");
        }

        // Load the context dependent phones. If the useCDUnits
        // property is false, the CD phones will not be created, but
        // the values still need to be read in from the file.

        String lastUnitName = "";
        Unit lastUnit = null;
        int[] lastStid = null;
        SenoneSequence lastSenoneSequence = null;

        for (int i = 0; i < numTri; i++) {
            String name = est.getString();
            String left = est.getString();
            String right = est.getString();
            String position = est.getString();
            String attribute = est.getString();
            int tmat = est.getInt("tmat");

            int[] stid = new int[numStatePerHMM - 1];

            for (int j = 0; j < numStatePerHMM - 1; j++) {
                stid[j] = est.getInt("j");
                assert stid[j] >= numContextIndependentTiedState
                        && stid[j] < numTiedState;
            }
            est.expectString("N");

            assert !left.equals("-");
            assert !right.equals("-");
            assert !position.equals("-");
            assert attribute.equals("n/a");
            assert tmat < numTiedTransitionMatrices;

            if (useCDUnits) {
                Unit unit;
                String unitName = (name + ' ' + left + ' ' + right);

                if (unitName.equals(lastUnitName)) {
                    unit = lastUnit;
                } else {
                    Unit[] leftContext = new Unit[1];
                    leftContext[0] = contextIndependentUnits.get(left);

                    Unit[] rightContext = new Unit[1];
                    rightContext[0] = contextIndependentUnits.get(right);

                    Context context = LeftRightContext.get(leftContext,
                            rightContext);
                    unit = unitManager.getUnit(name, false, context);
                }
                lastUnitName = unitName;
                lastUnit = unit;

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Loaded " + unit);
                }

                float[][] transitionMatrix = transitionsPool.get(tmat);

                SenoneSequence ss = lastSenoneSequence;
                if (ss == null || !sameSenoneSequence(stid, lastStid)) {
                    ss = getSenoneSequence(stid);
                }
                lastSenoneSequence = ss;
                lastStid = stid;

                HMM hmm = new SenoneHMM(unit, ss, transitionMatrix,
                        HMMPosition.lookup(position));
                hmmManager.put(hmm);
            }
        }

        est.close();
    }

    /**
     * Returns true if the given senone sequence IDs are the same.
     * 
     * @return true if the given senone sequence IDs are the same, false
     *         otherwise
     */
    protected boolean sameSenoneSequence(int[] ssid1, int[] ssid2) {
        if (ssid1.length == ssid2.length) {
            for (int i = 0; i < ssid1.length; i++) {
                if (ssid1[i] != ssid2[i]) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Gets the senone sequence representing the given senones.
     * 
     * @param stateid
     *            is the array of senone state ids
     * @return the senone sequence associated with the states
     */
    protected SenoneSequence getSenoneSequence(int[] stateid) {
        Senone[] senones = new Senone[stateid.length];
        for (int i = 0; i < stateid.length; i++) {
            senones[i] = senonePool.get(stateid[i]);
        }
        return new SenoneSequence(senones);
    }

    /**
     * Loads the mixture weights (Binary).
     * 
     * @param path
     *            the path to the mixture weight file
     * @param floor
     *            the minimum mixture weight allowed
     * @return a pool of mixture weights
     * @throws FileNotFoundException
     *             if a file cannot be found
     * @throws IOException
     *             if an error occurs while loading the data
     */
    protected Pool<float[]> loadMixtureWeights(String path, float floor)
            throws IOException, URISyntaxException {
        logger.fine("Loading mixture weights from: " + path);

        Properties props = new Properties();

        DataInputStream dis = readS3BinaryHeader(path, props);

        String version = props.getProperty("version");

        if (version == null || !version.equals(MIXW_FILE_VERSION)) {
            throw new IOException("Unsupported version in " + path);
        }

        String checksum = props.getProperty("chksum0");
        boolean doCheckSum = (checksum != null && checksum.equals("yes"));
        resetChecksum();

        Pool<float[]> pool = new Pool<float[]>(path);

        int numStates = readInt(dis);
        int numStreams = readInt(dis);
        int numGaussiansPerState = readInt(dis);
        int numValues = readInt(dis);

        logger.fine("Number of states " + numStates);
        logger.fine("Number of streams " + numStreams);
        logger.fine("Number of gaussians per state " + numGaussiansPerState);

        assert numValues == numStates * numStreams * numGaussiansPerState;

        pool.setFeature(NUM_SENONES, numStates);
        pool.setFeature(NUM_STREAMS, numStreams);
        pool.setFeature(NUM_GAUSSIANS_PER_STATE, numGaussiansPerState);

        if (numStreams != 1) {
            for (int i = 0; i < numStates; i++) {
                float[] logMixtureWeight = new float[numGaussiansPerState
                        * numStreams];
                for (int j = 0; j < numStreams; j++) {
                    float[] logStreamMixtureWeight = readFloatArray(dis,
                            numGaussiansPerState);
                    Utilities.normalize(logStreamMixtureWeight);
                    Utilities.floorData(logStreamMixtureWeight, floor);
                    logMath.linearToLog(logStreamMixtureWeight);
                    System.arraycopy(logStreamMixtureWeight, 0,
                            logMixtureWeight, numGaussiansPerState * j,
                            numGaussiansPerState);
                }
                pool.put(i, logMixtureWeight);
            }
        } else {
            for (int i = 0; i < numStates; i++) {
                float[] logMixtureWeight = readFloatArray(dis,
                        numGaussiansPerState);
                Utilities.normalize(logMixtureWeight);
                Utilities.floorData(logMixtureWeight, floor);
                logMath.linearToLog(logMixtureWeight);
                pool.put(i, logMixtureWeight);

            }

        }

        validateChecksum(dis, doCheckSum);

        dis.close();
        return pool;
    }

    /**
     * Loads the transition matrices (Binary).
     * 
     * @param path
     *            the path to the transitions matrices
     * @return a pool of transition matrices
     * @throws FileNotFoundException
     *             if a file cannot be found
     * @throws IOException
     *             if an error occurs while loading the data
     */
    protected Pool<float[][]> loadTransitionMatrices(String path)
            throws IOException, URISyntaxException {
        logger.fine("Loading transition matrices from: " + path);

        Properties props = new Properties();
        DataInputStream dis = readS3BinaryHeader(path, props);

        String version = props.getProperty("version");

        if (version == null || !version.equals(TMAT_FILE_VERSION)) {
            throw new IOException("Unsupported version in " + path);
        }

        String checksum = props.getProperty("chksum0");
        boolean doCheckSum = (checksum != null && checksum.equals("yes"));
        resetChecksum();

        Pool<float[][]> pool = new Pool<float[][]>(path);

        int numMatrices = readInt(dis);
        int numRows = readInt(dis);
        int numStates = readInt(dis);
        int numValues = readInt(dis);

        assert numValues == numStates * numRows * numMatrices;

        for (int i = 0; i < numMatrices; i++) {
            float[][] tmat = new float[numStates][];
            // last row should be zeros
            tmat[numStates - 1] = new float[numStates];
            logMath.linearToLog(tmat[numStates - 1]);

            for (int j = 0; j < numRows; j++) {
                tmat[j] = readFloatArray(dis, numStates);
                Utilities.nonZeroFloor(tmat[j], 0f);
                Utilities.normalize(tmat[j]);
                logMath.linearToLog(tmat[j]);
            }
            pool.put(i, tmat);
        }

        validateChecksum(dis, doCheckSum);

        dis.close();
        return pool;
    }

    /**
     * Loads the transform matrices (Binary).
     * 
     * @param path
     *            the path to the transform matrix
     * @return a transform matrix
     * @throws java.io.FileNotFoundException
     *             if a file cannot be found
     * @throws java.io.IOException
     *             if an error occurs while loading the data
     */
    protected float[][] loadTransformMatrix(String path) throws IOException {
        logger.fine("Loading transform matrix from: " + path);

        Properties props = new Properties();

        DataInputStream dis;
        try {
            dis = readS3BinaryHeader(path, props);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            return null;
        }

        String version = props.getProperty("version");

        if (version == null || !version.equals(TRANSFORM_FILE_VERSION)) {
            throw new IOException("Unsupported version in " + path);
        }

        String checksum = props.getProperty("chksum0");
        boolean doCheckSum = (checksum != null && checksum.equals("yes"));
        resetChecksum();

        readInt(dis);
        int numRows = readInt(dis);
        int numValues = readInt(dis);
        int num = readInt(dis);

        assert num == numRows * numValues;

        float[][] result = new float[numRows][];
        for (int i = 0; i < numRows; i++) {
            result[i] = readFloatArray(dis, numValues);
        }

        validateChecksum(dis, doCheckSum);

        dis.close();
        return result;
    }

    public Pool<float[]> getMeansPool() {
        return meansPool;
    }

    public Pool<float[][]> getMeansTransformationMatrixPool() {
        return meanTransformationMatrixPool;
    }

    public Pool<float[]> getMeansTransformationVectorPool() {
        return meanTransformationVectorPool;
    }

    public Pool<float[]> getVariancePool() {
        return variancePool;
    }

    public Pool<float[][]> getVarianceTransformationMatrixPool() {
        return varianceTransformationMatrixPool;
    }

    public Pool<float[]> getVarianceTransformationVectorPool() {
        return varianceTransformationVectorPool;
    }

    public Pool<float[]> getMixtureWeightPool() {
        return mixtureWeightsPool;
    }

    public Pool<float[][]> getTransitionMatrixPool() {
        return transitionsPool;
    }

    public float[][] getTransformMatrix() {
        return transformMatrix;
    }

    public Pool<Senone> getSenonePool() {
        return senonePool;
    }

    public int getLeftContextSize() {
        return CONTEXT_SIZE;
    }

    public int getRightContextSize() {
        return CONTEXT_SIZE;
    }

    public HMMManager getHMMManager() {
        return hmmManager;
    }

    public void logInfo() {
        logger.info("Loading tied-state acoustic model from: " + location);
        meansPool.logInfo(logger);
        variancePool.logInfo(logger);
        transitionsPool.logInfo(logger);
        senonePool.logInfo(logger);

        if (meanTransformationMatrixPool != null)
            meanTransformationMatrixPool.logInfo(logger);
        if (meanTransformationVectorPool != null)
            meanTransformationVectorPool.logInfo(logger);
        if (varianceTransformationMatrixPool != null)
            varianceTransformationMatrixPool.logInfo(logger);
        if (varianceTransformationVectorPool != null)
            varianceTransformationVectorPool.logInfo(logger);

        mixtureWeightsPool.logInfo(logger);
        senonePool.logInfo(logger);
        logger.info("Context Independent Unit Entries: "
                + contextIndependentUnits.size());
        hmmManager.logInfo(logger);
    }

    public Properties getProperties() {
        return modelProps;
    }

    private Properties loadModelProps(String path)
            throws MalformedURLException, IOException, URISyntaxException {
        Properties props = new Properties();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                getDataStream(path)));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] tokens = line.split(" ");
            props.put(tokens[0], tokens[1]);
        }
        return props;
    }
}
