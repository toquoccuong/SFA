// Copyright (c) 2017 - Patrick Schäfer (patrick.schaefer@hu-berlin.de)
// Distributed under the GLP 3.0 (See accompanying file LICENSE)
package sfa.transformation;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.cursors.IntIntCursor;
import com.carrotsearch.hppc.cursors.IntLongCursor;
import com.carrotsearch.hppc.cursors.LongDoubleCursor;
import sfa.classification.Classifier;
import sfa.classification.ParallelFor;
import sfa.timeseries.MultiVariateTimeSeries;
import sfa.timeseries.TimeSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The WEASEL+MUSE-Model as published in
 * <p>
 * Schäfer, P., Leser, U.: Multivariate Time Series Classification
 * with WEASEL+MUSE. arXiv 2017
 * http://arxiv.org/abs/1711.11343
 */
public class MUSE {

  public int alphabetSize;
  public int maxF;
  public SFA.HistogramType histogramType = null;

  public int[] windowLengths;
  public boolean normMean;
  public boolean lowerBounding;
  public SFA[] signature;
  public Dictionary dict;

  public final static int BLOCKS;

  static {
    Runtime runtime = Runtime.getRuntime();
    if (runtime.availableProcessors() <= 4) {
      BLOCKS = 8;
    } else {
      BLOCKS = runtime.availableProcessors();
    }
  }

  /**
   * Create a WEASEL model.
   *
   * @param maxF          Length of the SFA words
   * @param maxS          alphabet size
   * @param histogramType histogram types (EQUI-Depth and/or EQUI-Frequency) to use
   * @param windowLengths the set of window lengths to use for extracting SFA words from
   *                      time series.
   * @param normMean      set to true, if mean should be set to 0 for a window
   * @param lowerBounding set to true, if the Fourier transform should be normed (typically
   *                      used to lower bound / mimic Euclidean distance).
   */
  public MUSE(
      int maxF,
      int maxS,
      SFA.HistogramType histogramType,
      int[] windowLengths,
      boolean normMean,
      boolean lowerBounding) {
    this.maxF = maxF + maxF % 2; // even number
    this.alphabetSize = maxS;
    this.windowLengths = windowLengths;
    this.normMean = normMean;
    this.lowerBounding = lowerBounding;
    this.dict = new Dictionary();
    this.signature = new SFA[windowLengths.length];
    this.histogramType = histogramType;
  }

  /**
   * The MUSE model: a histogram of SFA word and bi-gram frequencies
   */
  public static class BagOfBigrams {
    public IntLongHashMap bob;
    public Double label;

    public BagOfBigrams(int size, Double label) {
      this.bob = new IntLongHashMap(size);
      this.label = label;
    }
  }

  /**
   * Create SFA words and bigrams for all samples
   *
   * @param samples
   * @return
   */
  public int[][][] createWords(final MultiVariateTimeSeries[] samples) {
    // create bag of words for each window length
    final int[][][] words = new int[this.windowLengths.length][samples.length][];
    ParallelFor.withIndex(BLOCKS, new ParallelFor.Each() {
      @Override
      public void run(int id, AtomicInteger processed) {
        for (int w = 0; w < MUSE.this.windowLengths.length; w++) {
          if (w % BLOCKS == id) {
            words[w] = createWords(samples, w);
          }
        }
      }
    });
    return words;
  }

  /**
   * Create SFA words and bigrams for all samples
   *
   * @param mtsSamples
   * @return
   */
  private int[/*window size*/][] createWords(final MultiVariateTimeSeries[] mtsSamples, final int index) {

    // SFA quantization
    if (this.signature[index] == null) {
      this.signature[index] = new SFA(this.histogramType, true);
      this.signature[index].fitWindowing(
          mtsSamples, this.windowLengths[index], this.maxF, this.alphabetSize, this.normMean, this.lowerBounding);
    }

    // create words
    final int[][] words = new int[mtsSamples.length * mtsSamples[0].getDimensions()][];
    int pos = 0;
    for (MultiVariateTimeSeries mts : mtsSamples) {
      for (TimeSeries timeSeries : mts.timeSeries) {
        if (timeSeries.getLength() >= this.windowLengths[index]) {
          words[pos] = this.signature[index].transformWindowingInt(timeSeries, this.maxF);
        } else {
          words[pos] = new int[]{};
        }
        pos++;
      }
    }

    return words;
  }

  /**
   * Create words and bi-grams for all window lengths
   */
  public BagOfBigrams[] createBagOfPatterns(
      final int[][][] words,
      final MultiVariateTimeSeries[] samples,
      final int dimensionality,
      final int wordLength) {
    List<BagOfBigrams> bagOfPatterns = new ArrayList<BagOfBigrams>(
        samples[0].getDimensions() * samples.length);

    final byte usedBits = (byte) Classifier.Words.binlog(this.alphabetSize);

//    final long mask = (usedBits << wordLength) - 1l;
    final long mask = (1L << (usedBits * wordLength)) - 1L;

    // iterate all samples in pairs of 'dimensionality'
    // and create a bag of bigrams each
    for (int dim = 0, j = 0; dim < samples.length; dim++, j += dimensionality) {
      BagOfBigrams bop = new BagOfBigrams(100, samples[dim].getLabel());

      // create subsequences
      for (int w = 0; w < this.windowLengths.length; w++) {
        if (this.windowLengths[w] >= wordLength) {
          for (int d = 0; d < dimensionality; d++) {
            String dLabel = String.valueOf(d);
            for (int offset = 0; offset < words[w][j + d].length; offset++) {
              String word = w + "_" + dLabel + "_" + ((words[w][j + d][offset] & mask));
              int dict = this.dict.getWord(word);
              bop.bob.putOrAdd(dict, 1, 1);

              // add 2-grams
              if (offset - this.windowLengths[w] >= 0) {
                String prevWord = w + "_" + dLabel + "_" + ((words[w][j + d][offset - this.windowLengths[w]] & mask));
                int newWord = this.dict.getWord(prevWord + "_" + word);
                bop.bob.putOrAdd(newWord, 1, 1);
              }
            }
          }
        }
      }
      bagOfPatterns.add(bop);
    }
    return bagOfPatterns.toArray(new BagOfBigrams[]{});
  }

  /**
   * Implementation based on:
   * https://github.com/scikit-learn/scikit-learn/blob/c957249/sklearn/feature_selection/univariate_selection.py#L170
   */
  public void filterChiSquared(final BagOfBigrams[] bob, double chi_limit) {
    // class frequencies
    LongIntHashMap classFrequencies = new LongIntHashMap();
    for (BagOfBigrams ts : bob) {
      long label = ts.label.longValue();
      classFrequencies.putOrAdd(label, 1, 1);
    }

    // Chi2 Test
    IntIntHashMap featureCount = new IntIntHashMap(bob[0].bob.size());
    LongDoubleHashMap classProb = new LongDoubleHashMap(10);
    LongIntHashMap observed = new LongIntHashMap(bob[0].bob.size());
    IntDoubleHashMap chiSquare = new IntDoubleHashMap(bob[0].bob.size());

    // count number of samples with this word
    for (BagOfBigrams bagOfPattern : bob) {
      long label = bagOfPattern.label.longValue();
      for (IntLongCursor word : bagOfPattern.bob) {
        if (word.value > 0) {
          featureCount.putOrAdd(word.key, 1, 1);
          long key = label << 32 | word.key;
          observed.putOrAdd(key, 1, 1);
        }
      }
    }

    // samples per class
    for (BagOfBigrams bagOfPattern : bob) {
      long label = bagOfPattern.label.longValue();
      classProb.putOrAdd(label, 1, 1);
    }

    // chi square: observed minus expected occurence
    for (LongDoubleCursor prob : classProb) {
      prob.value /= (double) bob.length; // (double) frequencies.get(prob.key);

      for (IntIntCursor feature : featureCount) {
        long key = prob.key << 32 | feature.key;
        double expected = prob.value * feature.value;

        double chi = observed.get(key) - expected;
        double newChi = chi * chi / expected;
        if (newChi >= chi_limit
            && newChi > chiSquare.get(feature.key)) {
          chiSquare.put(feature.key, newChi);
        }
      }
    }

    // best elements above limit
    for (int j = 0; j < bob.length; j++) {
      for (IntLongCursor cursor : bob[j].bob) {
        if (chiSquare.get(cursor.key) < chi_limit) {
          bob[j].bob.values[cursor.index] = 0;
        }
      }
    }

    // chi square reduces keys substantially => remap
    this.dict.remap(bob);
  }

  /**
   * A dictionary that maps each SFA word to an integer.
   * <p>
   * Condenses the SFA word space.
   */
  public static class Dictionary {
    ObjectIntHashMap<String> dict;
    IntIntHashMap dictChi;

    public Dictionary() {
      this.dict = new ObjectIntHashMap<String>();
      this.dictChi = new IntIntHashMap();
    }

    public void reset() {
      this.dict = new ObjectIntHashMap<String>();
      this.dictChi = new IntIntHashMap();
    }

    public int getWord(String word) {
      int index = 0;
      int newWord = -1;
      if ((index = this.dict.indexOf(word)) > -1) {
        newWord = this.dict.indexGet(index);
      } else {
        newWord = this.dict.size() + 1;
        this.dict.put(word, newWord);
      }
      return newWord;
    }

    public int getWordChi(int word) {
      int index = 0;
      if ((index = this.dictChi.indexOf(word)) > -1) {
        return this.dictChi.indexGet(index);
      } else {
        int newWord = this.dictChi.size() + 1;
        this.dictChi.put(word, newWord);
        return newWord;
      }
    }

    public int size() {
      if (!this.dictChi.isEmpty()) {
        return this.dictChi.size();
      } else {
        return this.dict.size();
      }
    }

    public void remap(final BagOfBigrams[] bagOfPatterns) {
      for (int j = 0; j < bagOfPatterns.length; j++) {
        IntLongHashMap oldMap = bagOfPatterns[j].bob;
        bagOfPatterns[j].bob = new IntLongHashMap(oldMap.size());
        for (IntLongCursor word : oldMap) {
          if (word.value > 0) {
            bagOfPatterns[j].bob.put(getWordChi(word.key), word.value);
          }
        }
      }
    }
  }
}
