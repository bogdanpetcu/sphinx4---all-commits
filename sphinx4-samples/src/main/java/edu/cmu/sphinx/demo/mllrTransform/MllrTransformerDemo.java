package edu.cmu.sphinx.demo.mllrTransform;

import java.io.InputStream;

import edu.cmu.sphinx.api.Configuration;
import edu.cmu.sphinx.api.SpeechResult;
import edu.cmu.sphinx.api.StreamSpeechRecognizer;
<<<<<<< HEAD
import edu.cmu.sphinx.decoder.adaptation.DensityFileData;
=======
>>>>>>> Added transform object and made necessary modifications. Isolated the object from its process of construction.
import edu.cmu.sphinx.decoder.adaptation.MllrEstimation;
import edu.cmu.sphinx.decoder.adaptation.MllrTransformer;
import edu.cmu.sphinx.decoder.adaptation.Transform;
import edu.cmu.sphinx.demo.transcriber.TranscriberDemo;
import edu.cmu.sphinx.linguist.acoustic.tiedstate.Sphinx3Loader;
import edu.cmu.sphinx.result.WordResult;

public class MllrTransformerDemo {

	public static void main(String[] args) throws Exception {

		Configuration configuration = new Configuration();

		configuration.setAcousticModelPath("/home/bogdanpetcu/RSoC/en-us");
		configuration
				.setDictionaryPath("resource:/edu/cmu/sphinx/models/acoustic/wsj/dict/cmudict.0.6d");
		configuration
				.setLanguageModelPath("resource:/edu/cmu/sphinx/models/language/en-us.lm.dmp");

		StreamSpeechRecognizer recognizer = new StreamSpeechRecognizer(
				configuration);
		InputStream stream = TranscriberDemo.class
				.getResourceAsStream("/edu/cmu/sphinx/demo/countsCollector/out.wav");
		recognizer.startRecognition(stream);

		SpeechResult result;
		MllrTransformer mt;
		Sphinx3Loader loader = (Sphinx3Loader) recognizer.getLoader();
<<<<<<< HEAD
		DensityFileData means = new DensityFileData("", -Float.MAX_VALUE,
				loader, false);
		means.getMeansFromLoader();

		MllrEstimation me = new MllrEstimation("", 1, "/home/bogdanpetcu/mllr_mat2", false, "", false,
				loader);

		while ((result = recognizer.getResult()) != null) {
			me.addCounts(result.getResult());
=======
		
		MllrEstimation me = new MllrEstimation(loader);
		
		while ((result = recognizer.getResult()) != null) {
			me.collect(result.getResult());
>>>>>>> Added transform object and made necessary modifications. Isolated the object from its process of construction.

			System.out.format("Hypothesis: %s\n", result.getHypothesis());

			System.out.println("List of recognized words and their times:");
			for (WordResult r : result.getWords()) {
				System.out.println(r);
			}

			System.out.println("Best 3 hypothesis:");
			for (String s : result.getNbest(3))
				System.out.println(s);

			System.out.println("Lattice contains "
					+ result.getLattice().getNodes().size() + " nodes");
		}

<<<<<<< HEAD
<<<<<<< HEAD
=======
		MllrEstimation me = new MllrEstimation("/home/bogdanpetcu/mllrmat",loader);

>>>>>>> implemented direct counts collecting in MllrEstimation.java and ClustersEstimation.java. This way the program uses less memory than the previous form when the counts were stored before they were used in computing the transform
		recognizer.stopRecognition();
		me.estimateMatrices();
		me.createMllrFile();
		mt = new MllrTransform(means, me.getA(), me.getB(),
				"/home/bogdanpetcu/means");
		mt.transform();
		mt.writeToFile();
		
=======
		recognizer.stopRecognition();
		
		Transform transform = new Transform(loader);
		transform.load(me);

		mt = new MllrTransformer(loader, transform);
		mt.applyTransform();
		mt.createNewMeansFile("/home/bogdanpetcu/test");

>>>>>>> Added transform object and made necessary modifications. Isolated the object from its process of construction.
	}
}
