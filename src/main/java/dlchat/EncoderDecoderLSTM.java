package dlchat;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ArrayUtils;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration.GraphBuilder;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.graph.MergeVertex;
import org.deeplearning4j.nn.conf.graph.rnn.DuplicateToTimeSeriesVertex;
import org.deeplearning4j.nn.conf.graph.rnn.LastTimeStepVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.EmbeddingLayer;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.GraphVertex;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

public class EncoderDecoderLSTM {

    /*
     * This is a seq2seq encoder-decoder LSTM model made according to the Google's paper: [1] The model tries to predict the next dialog
     * line using the provided one. It learns on the Cornell Movie Dialogs corpus. Unlike simple char RNNs this model is more sophisticated
     * and theoretically, given enough time and data, can deduce facts from raw text. Your mileage may vary. This particular code is based
     * on AdditionRNN but heavily changed to be used with a huge amount of possible tokens (10-20k), it also utilizes the decoder input
     * unlike AdditionRNN.
     * 
     * Use the get_data.sh script to download, extract and optimize the train data. It's been only tested on Linux, it could work on OS X or
     * even on Windows 10 in the Ubuntu shell.
     * 
     * Special tokens used:
     * 
     * <unk> - replaces any word or other token that's not in the dictionary (too rare to be included or completely unknown)
     * 
     * <eos> - end of sentence, used only in the output to stop the processing; the model input and output length is limited by the ROW_SIZE
     * constant.
     * 
     * <go> - used only in the decoder input as the first token before the model produced anything
     * 
     * The architecture is like this: Input => Embedding Layer => Encoder => Decoder => Output (softmax)
     * 
     * The encoder layer produces a so called "thought vector" that contains a compressed representation of the input. Depending on that
     * vector the model produces different sentences even if they start with the same token. There's one more input, connected directly to
     * the decoder layer, it's used to provide the previous token of the output. For the very first output token we send a special <go>
     * token there, on the next iteration we use the token that the model produced the last time. On the training stage everything is
     * simple, we apriori know the desired output so the decoder input would be the same token set prepended with the <go> token and without
     * the last <eos> token. Example:
     * 
     * Input: "how" "do" "you" "do" "?"
     * 
     * Output: "I'm" "fine" "," "thanks" "!" "<eos>"
     * 
     * Decoder: "<go>" "I'm" "fine" "," "thanks" "!"
     * 
     * Actually, the input is reversed as per [2], the most important words are usually in the beginning of the phrase and they would get
     * more weight if supplied last (the model "forgets" tokens that were supplied "long ago"). The output and decoder input sequence
     * lengths are always equal. The input and output could be of any length (less than ROW_SIZE) so for purpose of batching we mask the
     * unused part of the row. The encoder and decoder networks work sequentially. First the encoder creates the thought vector, that is the
     * last activations of the layer. Those activations are then duplicated for as many time steps as there are elements in the output so
     * that every output element can have its own copy of the thought vector. Then the decoder starts working. It receives two inputs, the
     * thought vector made by the encoder and the token that it _should have produced_ (but usually it outputs something else so we have our
     * loss metric and can compute gradients for the backward pass) on the previous step (or <go> for the very first step). These two
     * vectors are simply concatenated by the merge vertex. The decoder's output goes to the softmax layer and that's it.
     * 
     * The test phase is much more tricky. We don't know the decoder input because we don't know the output yet (unlike in the train phase),
     * it could be anything. So we can't use methods like outputSingle() and have to do some manual work. Actually, we can but it would
     * require full restarts of the entire process, it's super slow and ineffective.
     * 
     * First, we do a single feed forward pass for the input with a single decoder element, <go>. We don't need the actual activations
     * except the "thought vector". It resides in the second merge vertex input (named "dup"). So we get it and store for the entire
     * response generation time. Then we put the decoder input (<go> for the first iteration) and the thought vector to the merge vertex
     * inputs and feed it forward. The result goes to the decoder layer, now with rnnTimeStep() method so that the internal layer state is
     * updated for the next iteration. The result is fed to the output softmax layer and then we sample it randomly (not with argMax(), it
     * tends to give a lot of same tokens in a row). The resulting token is looked up in the dictionary, printed to the stdout and then it
     * goes to the next iteration as the decoder input and so on until we get <eos>.
     *
     * To continue the training process from a specific batch number, enter it when prompted; batch numbers are printed after each processed
     * macrobatch. If you've changed the minibatch size after the last launch, recalculate the number accordingly, i.e. if you doubled the
     * minibatch size, specify half of the value and so on.
     * 
     * [1] https://arxiv.org/abs/1506.05869 A Neural Conversational Model
     * 
     * [2] https://papers.nips.cc/paper/5346-sequence-to-sequence-learning-with-neural-networks.pdf Sequence to Sequence Learning with
     * Neural Networks
     */

    public enum SaveState {
        NONE, READY, SAVING, SAVENOW
    }

    private final Map<String, Double> dict = new HashMap<>();
    private final Map<Double, String> revDict = new HashMap<>();
    private final String CHARS = "-\\/_&" + CorpusProcessor.SPECIALS;
    private List<List<Double>> corpus = new ArrayList<>();
    private static final int HIDDEN_LAYER_WIDTH = 1024; // this is purely empirical, affects performance and VRAM requirement
    private static final int EMBEDDING_WIDTH = 128; // one-hot vectors will be embedded to more dense vectors with this width
    private static final String CORPUS_FILENAME = "movie_lines.txt"; // filename of data corpus to learn
    private static final String MODEL_FILENAME = "rnn_train_movies.zip"; // filename of the model
    private static final String BACKUP_MODEL_FILENAME = "rnn_train_movies.bak.zip"; // filename of the previous version of the model (backup)
    private static final String DICTIONARY_FILENAME = "dictionary.txt";
    private static final int MINIBATCH_SIZE = 16;
    private static final Random rnd = new Random(new Date().getTime());
    private static final long SAVE_EACH_MS = TimeUnit.MINUTES.toMillis(10); // save the model with this period
    private static final long TEST_EACH_MS = TimeUnit.MINUTES.toMillis(1); // test the model with this period
    private static final int MAX_DICT = 40000; // this number of most frequent words will be used, unknown words (that are not in the
                                               // dictionary) are replaced with <unk> token
    private static final double LEARNING_RATE = 1e-2;
    private static final double RMS_DECAY = 0.95;
    private static final double L2 = 1e-5;
    private static final int ROW_SIZE = 20; // maximum line length in tokens
    private static final int GC_WINDOW = 500; // delay between garbage collections, try to reduce if you run out of VRAM or increase for
                                              // better performance
    private static final int MACROBATCH_SIZE = 20; // see CorpusIterator
    private static final boolean TMP_DATA_DIR = false;
    private SaveState saveState = SaveState.NONE;
    private static final boolean SAVE_ON_EXIT = true;
    private ComputationGraph net;

    public static void main(String[] args) throws Exception {
        new EncoderDecoderLSTM().run(args);
    }

    private void run(String[] args) throws Exception {
        File networkFile = new File(toTempPath(MODEL_FILENAME));
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (SAVE_ON_EXIT && saveState == SaveState.READY) {
                    saveState = SaveState.SAVENOW;
                    System.out.println(
                            "Wait for the current macrobatch to end, then the model will be saved and the program will terminate.");
                    while (saveState != SaveState.READY) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        Nd4j.getMemoryManager().setAutoGcWindow(GC_WINDOW);

        createDictionary();

        int offset = 0;
        if (networkFile.exists()) {
            System.out.println("Loading the existing network...");
            net = ModelSerializer.restoreComputationGraph(networkFile);
            offset = net.getConfiguration().getIterationCount();
            System.out.print("Enter d to start dialog or a number to continue training from that minibatch (press Enter to start from ["
                    + offset + "]: ");
            String input;
            try (Scanner scanner = new Scanner(System.in)) {
                input = scanner.nextLine();
                if (input.toLowerCase().equals("d")) {
                    startDialog(scanner);
                } else {
                    if (!input.isEmpty()) {
                        offset = Integer.valueOf(input);
                    }
                    net.getConfiguration().setIterationCount(offset);
                    test();
                }
            }
        } else {
            System.out.println("Creating a new network...");
            createComputationGraph();
        }
        System.out.println("Number of parameters: " + net.numParams());
        net.setListeners(new ScoreIterationListener(1));
        train(networkFile, offset);
    }

    private void createComputationGraph() {
        NeuralNetConfiguration.Builder builder = new NeuralNetConfiguration.Builder();
        builder.iterations(1).learningRate(LEARNING_RATE).rmsDecay(RMS_DECAY)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).miniBatch(true).updater(Updater.RMSPROP)
                .weightInit(WeightInit.XAVIER).gradientNormalization(GradientNormalization.RenormalizeL2PerLayer).regularization(true)
                .l2(L2);

        GraphBuilder graphBuilder = builder.graphBuilder().pretrain(false).backprop(true);
        graphBuilder.addInputs("inputLine", "decoderInput")
                .setInputTypes(InputType.recurrent(dict.size()), InputType.recurrent(dict.size()))
                .addLayer("embeddingEncoder", new EmbeddingLayer.Builder().nIn(dict.size()).nOut(EMBEDDING_WIDTH).build(), "inputLine")
                .addLayer("encoder",
                        new GravesLSTM.Builder().nIn(EMBEDDING_WIDTH).nOut(HIDDEN_LAYER_WIDTH).activation(Activation.TANH).build(),
                        "embeddingEncoder")
                .addVertex("thoughtVector", new LastTimeStepVertex("inputLine"), "encoder")
                .addVertex("dup", new DuplicateToTimeSeriesVertex("decoderInput"), "thoughtVector")
                .addVertex("merge", new MergeVertex(), "decoderInput", "dup")
                .addLayer("decoder",
                        new GravesLSTM.Builder().nIn(dict.size() + HIDDEN_LAYER_WIDTH).nOut(HIDDEN_LAYER_WIDTH).activation(Activation.TANH)
                                .build(),
                        "merge")
                .addLayer("output", new RnnOutputLayer.Builder().nIn(HIDDEN_LAYER_WIDTH).nOut(dict.size()).activation(Activation.SOFTMAX)
                        .lossFunction(LossFunctions.LossFunction.MCXENT).build(), "decoder")
                .setOutputs("output");

        net = new ComputationGraph(graphBuilder.build());
        net.init();
    }

    private void train(File networkFile, int offset) throws Exception {
        saveState = SaveState.READY;
        long lastSaveTime = System.currentTimeMillis();
        long lastTestTime = System.currentTimeMillis();
        CorpusIterator logsIterator = new CorpusIterator(corpus, MINIBATCH_SIZE, MACROBATCH_SIZE, dict.size(), ROW_SIZE);
        for (int epoch = 1; epoch < 10000; ++epoch) {
            System.out.println("Epoch " + epoch);
            if (epoch == 1) {
                logsIterator.setCurrentBatch(offset);
            } else {
                logsIterator.reset();
            }
            int lastPerc = 0;
            while (logsIterator.hasNextMacrobatch()) {
                long t1 = System.currentTimeMillis();
                net.fit(logsIterator);
                long t2 = System.currentTimeMillis();
                int batch = logsIterator.batch();
                System.out.println("Batch = " + batch + " / " + logsIterator.totalBatches() + " time = " + (t2 - t1));
                logsIterator.nextMacroBatch();
                int newPerc = (batch * 100 / logsIterator.totalBatches());
                if (newPerc != lastPerc) {
                    System.out.println("Epoch complete: " + newPerc + "%");
                    lastPerc = newPerc;
                }
                if (saveState == SaveState.SAVENOW) {
                    saveModel(networkFile, batch);
                    return;
                }
                if (System.currentTimeMillis() - lastSaveTime > SAVE_EACH_MS) {
                    saveModel(networkFile, batch);
                    lastSaveTime = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - lastTestTime > TEST_EACH_MS) {
                    test();
                    lastTestTime = System.currentTimeMillis();
                }
            }
        }
    }

    private void startDialog(Scanner scanner) throws IOException {
        System.out.println("Dialog started.");
        while (true) {
            System.out.print("In> ");
            // input line is appended to conform to the corpus format
            String line = appendInputLine(scanner.nextLine());
            CorpusProcessor dialogProcessor = new CorpusProcessor(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)), ROW_SIZE,
                    false) {
                @Override
                protected void processLine(String lastLine) {
                    List<String> words = new ArrayList<>();
                    tokenizeLine(lastLine, words, true);
                    List<Double> wordIdxs = new ArrayList<>();
                    if (wordsToIndexes(words, wordIdxs)) {
                        System.out.print("Got words: ");
                        for (Double idx : wordIdxs) {
                            System.out.print(revDict.get(idx) + " ");
                        }
                        System.out.println();
                        System.out.print("Out> ");
                        output(wordIdxs, true);
                    }
                }
            };
            setupCorpusProcessor(dialogProcessor);
            dialogProcessor.setDict(dict);
            dialogProcessor.start();
        }
    }

    private String appendInputLine(String line) {
        return "1 +++$+++ u11 +++$+++ m0 +++$+++ WALTER +++$+++ " + line + "\n";
        // return "me¦" + line + "\n";
    }

    private void saveModel(File networkFile, int batch) throws IOException {
        saveState = SaveState.SAVING;
        System.out.println("Saving the model...");
        System.gc();
        File backup = new File(toTempPath(BACKUP_MODEL_FILENAME));
        if (networkFile.exists()) {
            if (backup.exists()) {
                backup.delete();
            }
            networkFile.renameTo(backup);
        }
        ModelSerializer.writeModel(net, networkFile, true);
        System.gc();
        System.out.println("Done.");
        saveState = SaveState.READY;
    }

    private void test() {
        System.out.println("======================== TEST ========================");
        int selected = rnd.nextInt(corpus.size());
        List<Double> rowIn = new ArrayList<>(corpus.get(selected));
        System.out.print("In: ");
        for (Double idx : rowIn) {
            System.out.print(revDict.get(idx) + " ");
        }
        System.out.println();
        System.out.print("Out: ");
        output(rowIn, true);
        System.out.println("====================== TEST END ======================");
    }

    private void output(List<Double> rowIn, boolean printUnknowns) {
        net.rnnClearPreviousState();
        Collections.reverse(rowIn);
        INDArray in = Nd4j.create(ArrayUtils.toPrimitive(rowIn.toArray(new Double[0])), new int[] { 1, 1, rowIn.size() });
        double[] decodeArr = new double[dict.size()];
        decodeArr[2] = 1;
        INDArray decode = Nd4j.create(decodeArr, new int[] { 1, dict.size(), 1 });
        net.feedForward(new INDArray[] { in, decode }, false);
        org.deeplearning4j.nn.layers.recurrent.GravesLSTM decoder = (org.deeplearning4j.nn.layers.recurrent.GravesLSTM) net
                .getLayer("decoder");
        Layer output = net.getLayer("output");
        GraphVertex mergeVertex = net.getVertex("merge");
        INDArray thoughtVector = mergeVertex.getInputs()[1];
        for (int row = 0; row < ROW_SIZE; ++row) {
            mergeVertex.setInputs(decode, thoughtVector);
            INDArray merged = mergeVertex.doForward(false);
            INDArray activateDec = decoder.rnnTimeStep(merged);
            INDArray out = output.activate(activateDec, false);
            double d = rnd.nextDouble();
            double sum = 0.0;
            int idx = -1;
            for (int s = 0; s < out.size(1); s++) {
                sum += out.getDouble(0, s, 0);
                if (d <= sum) {
                    idx = s;
                    if (printUnknowns || s != 0) {
                        System.out.print(revDict.get((double) s) + " ");
                    }
                    break;
                }
            }
            if (idx == 1) {
                break;
            }
            double[] newDecodeArr = new double[dict.size()];
            newDecodeArr[idx] = 1;
            decode = Nd4j.create(newDecodeArr, new int[] { 1, dict.size(), 1 });
        }
        System.out.println();
    }

    private void createDictionary() throws IOException, FileNotFoundException {
        double idx = 3.0;
        dict.put("<unk>", 0.0);
        revDict.put(0.0, "<unk>");
        dict.put("<eos>", 1.0);
        revDict.put(1.0, "<eos>");
        dict.put("<go>", 2.0);
        revDict.put(2.0, "<go>");
        for (char c : CHARS.toCharArray()) {
            if (!dict.containsKey(c)) {
                dict.put(String.valueOf(c), idx);
                revDict.put(idx, String.valueOf(c));
                ++idx;
            }
        }
        System.out.println("Building the dictionary...");
        CorpusProcessor corpusProcessor = new CorpusProcessor(toTempPath(CORPUS_FILENAME), ROW_SIZE, true);
        setupCorpusProcessor(corpusProcessor);
        corpusProcessor.start();
        Map<String, Double> freqs = corpusProcessor.getFreq();
        Set<String> dictSet = new TreeSet<>(); // the tokens order is preserved for TreeSet
        Map<Double, Set<String>> freqMap = new TreeMap<>(new Comparator<Double>() {

            @Override
            public int compare(Double o1, Double o2) {
                return (int) (o2 - o1);
            }
        }); // tokens of the same frequency fall under the same key, the order is reversed so the most frequent tokens go first
        for (Entry<String, Double> entry : freqs.entrySet()) {
            Set<String> set = freqMap.get(entry.getValue());
            if (set == null) {
                set = new TreeSet<>(); // tokens of the same frequency would be sorted alphabetically
                freqMap.put(entry.getValue(), set);
            }
            set.add(entry.getKey());
        }
        int cnt = 0;
        dictSet.addAll(dict.keySet());
        // get most frequent tokens and put them to dictSet
        for (Entry<Double, Set<String>> entry : freqMap.entrySet()) {
            for (String val : entry.getValue()) {
                if (dictSet.add(val) && ++cnt >= MAX_DICT) {
                    break;
                }
            }
            if (cnt >= MAX_DICT) {
                break;
            }
        }
        // all of the above means that the dictionary with the same MAX_DICT constraint and made from the same source file will always be
        // the same, the tokens always correspond to the same number so we don't need to save/restore the dictionary
        System.out.println("Dictionary is ready, size is " + dictSet.size());
        // index the dictionary and build the reverse dictionary for lookups
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(DICTIONARY_FILENAME))) {
            for (String word : dictSet) {
                bw.write(word + "\n");
                if (!dict.containsKey(word)) {
                    dict.put(word, idx);
                    revDict.put(idx, word);
                    ++idx;
                }
            }
        }
        System.out.println("Total dictionary size is " + dict.size() + ". Processing the dataset...");
        corpusProcessor = new CorpusProcessor(toTempPath(CORPUS_FILENAME), ROW_SIZE, false) {
            @Override
            protected void processLine(String lastLine) {
                ArrayList<String> words = new ArrayList<>();
                tokenizeLine(lastLine, words, true);
                if (!words.isEmpty()) {
                    List<Double> wordIdxs = new ArrayList<>();
                    if (wordsToIndexes(words, wordIdxs)) {
                        corpus.add(wordIdxs);
                    }
                }
            }
        };
        setupCorpusProcessor(corpusProcessor);
        corpusProcessor.setDict(dict);
        corpusProcessor.start();
        System.out.println("Done. Corpus size is " + corpus.size());
    }

    private void setupCorpusProcessor(CorpusProcessor corpusProcessor) {
        // corpusProcessor.setFormatParams("¦", 2, 0, 1);
    }

    private String toTempPath(String path) {
        if (!TMP_DATA_DIR) {
            return path;
        }
        return System.getProperty("java.io.tmpdir") + "/" + path;
    }

}
