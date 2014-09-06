package edu.cmu.sphinx.demo.Transformer;

import java.io.InputStream;

import edu.cmu.sphinx.api.Configuration;
import edu.cmu.sphinx.api.SpeechResult;
import edu.cmu.sphinx.api.StreamSpeechRecognizer;
import edu.cmu.sphinx.decoder.adaptation.ClusteredDensityFileData;
import edu.cmu.sphinx.decoder.adaptation.Stats;
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
				.setDictionaryPath("/home/bogdanpetcu/RSoC/wsj/dict/cmudict.0.6d");
		configuration
				.setLanguageModelPath("resource:/edu/cmu/sphinx/models/language/en-us.lm.dmp");

		StreamSpeechRecognizer recognizer = new StreamSpeechRecognizer(
				configuration);
		
		InputStream stream = TranscriberDemo.class
				.getResourceAsStream("/edu/cmu/sphinx/demo/countsCollector/BillGates5Mins.wav");
		recognizer.startRecognition(stream);

		Sphinx3Loader loader = (Sphinx3Loader) recognizer.getLoader();
		
		ClusteredDensityFileData cm = new ClusteredDensityFileData(loader, 10);

		Stats stats = new Stats(loader, 10, cm);
		
		Transform transform = new Transform(loader, 10);
		
		SpeechResult result;
		
		while ((result = recognizer.getResult()) != null) {
			stats.collect(result.getResult());
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

		recognizer.stopRecognition();
		
		transform.update(stats);
		
		MllrTransformer rt2 = new MllrTransformer(loader, transform, cm);
		rt2.transformMean();
		rt2.createNewMeansFile("/home/bogdanpetcu/TESTARE/testBillGates");
	
	}

}
