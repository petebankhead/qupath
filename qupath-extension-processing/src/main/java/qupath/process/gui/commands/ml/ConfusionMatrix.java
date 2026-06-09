package qupath.process.gui.commands.ml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: Add tests (there's a fairly high chance there are errors here)
class ConfusionMatrix<T> {

    private final Map<T, Map<T, AtomicInteger>> matrix;
    private int count;

    ConfusionMatrix() {
        this.matrix = new LinkedHashMap<>();
    }

    /**
     * Accumulate one prediction.
     *
     * @param i actual label
     * @param j predicted label
     */
    synchronized void accumulate(T i, T j) {
        var counter = matrix.computeIfAbsent(i, _ -> new LinkedHashMap<>())
                .computeIfAbsent(j, _ -> new AtomicInteger(0));
        counter.incrementAndGet();
        count++;
    }

    synchronized int getTotal() {
        return count;
    }

    private synchronized int getCount(T i, T j) {
        if (!matrix.containsKey(i))
            return 0;
        return matrix.get(i).computeIfAbsent(j, _ -> new AtomicInteger(0)).get();
    }

    synchronized int getTruePositives(T i) {
        return getCount(i, i);
    }

    synchronized int getFalsePositives(T i) {
        int sum = 0;
        for (var row : matrix.keySet()) {
            if (!Objects.equals(i, row)) {
                sum += getCount(row, i);
            }
        }
        return sum;
    }

    synchronized int getFalseNegatives(T i) {
        int sum = 0;
        for (var col : matrix.keySet()) {
            if (!Objects.equals(i, col)) {
                sum += getCount(i, col);
            }
        }
        return sum;
    }

    synchronized double getPrecision(T i) {
        double truePos = getTruePositives(i);
        double falsePos = getFalsePositives(i);
        return truePos / (truePos + falsePos);
    }

    synchronized double getRecall(T i) {
        double truePos = getTruePositives(i);
        double falseNeg = getFalseNegatives(i);
        return truePos / (truePos + falseNeg);
    }

    synchronized double getF1(T i) {
        double precision = getPrecision(i);
        double recall = getRecall(i);
        return 2 * (precision * recall) / (precision + recall);
    }

    /**
     * Get average (unweighted) F1.
     *
     * @return
     */
    synchronized double getF1() {
        return matrix.keySet().stream().mapToDouble(this::getF1).average().orElse(Double.NaN);
    }

    /**
     * Get average (unweighted) accuracy.
     *
     * @return
     */
    synchronized double getAccuracy() {
        double nTruePositives = matrix.keySet().stream().mapToDouble(this::getTruePositives).sum();
        return nTruePositives / getTotal();
    }

}
