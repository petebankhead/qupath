package qupath.process.gui.commands.ml;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static <T> ConfusionMatrix<T> sum(Collection<? extends ConfusionMatrix<T>> matrices) {
        var sum = new ConfusionMatrix<T>();
        for (var mat : matrices) {
            var labels = mat.getLabels();
            for (var row : labels) {
                for (var col : labels) {
                    sum.accumulate(row, col, mat.getCount(row, col));
                }
            }
        }
        return sum;
    }

    /**
     * Accumulate one prediction.
     *
     * @param i actual label
     * @param j predicted label
     */
    synchronized void accumulate(T i, T j) {
        accumulate(i, j, 1);
    }

    synchronized void accumulate(T i, T j, int toAdd) {
        var counter = matrix.computeIfAbsent(i, _ -> new LinkedHashMap<>())
                .computeIfAbsent(j, _ -> new AtomicInteger(0));
        counter.addAndGet(toAdd);
        count += toAdd;
    }

    synchronized List<T> getLabels() {
        return List.copyOf(matrix.keySet());
    }

    synchronized int getTotal() {
        return count;
    }

    public synchronized double getNormalizedCount(T row, T col) {
        return (double)getCount(row, col) / getRowSum(row);
    }

    public synchronized int getRowSum(T row) {
        int sum = 0;
        for (var col : getLabels()) {
            sum += getCount(row, col);
        }
        return sum;
    }

    public synchronized int getCount(T i, T j) {
        if (!matrix.containsKey(i))
            return 0;
        return matrix.get(i).computeIfAbsent(j, _ -> new AtomicInteger(0)).get();
    }

    synchronized int getTruePositives(T i) {
        return getCount(i, i);
    }

    synchronized int getTruePositives() {
        return getLabels().stream().mapToInt(this::getTruePositives).sum();
    }

    synchronized int getFalsePositives(T col) {
        int sum = 0;
        for (var row : getLabels()) {
            if (!Objects.equals(col, row)) {
                sum += getCount(row, col);
            }
        }
        return sum;
    }

    synchronized int getFalsePositives() {
        return getLabels().stream().mapToInt(this::getFalsePositives).sum();
    }

    synchronized int getFalseNegatives(T row) {
        int sum = 0;
        for (var col : getLabels()) {
            if (!Objects.equals(row, col)) {
                sum += getCount(row, col);
            }
        }
        return sum;
    }

    synchronized int getFalseNegatives() {
        return getLabels().stream().mapToInt(this::getFalseNegatives).sum();
    }

    synchronized double getPrecision(T i) {
        double truePos = getTruePositives(i);
        double falsePos = getFalsePositives(i);
        return truePos / (truePos + falsePos);
    }

    synchronized double getPrecision() {
        return getLabels()
                .stream()
                .mapToDouble(this::getPrecision)
                .average()
                .orElse(Double.NaN);
//        double truePos = getTruePositives();
//        double falsePos = getFalsePositives();
//        return truePos / (truePos + falsePos);
    }

    synchronized double getRecall(T i) {
        double truePos = getTruePositives(i);
        double falseNeg = getFalseNegatives(i);
        return truePos / (truePos + falseNeg);
    }

    synchronized double getRecall() {
        return getLabels()
                .stream()
                .mapToDouble(this::getRecall)
                .average()
                .orElse(Double.NaN);
//        double truePos = getTruePositives();
//        double falseNeg = getFalseNegatives();
//        return truePos / (truePos + falseNeg);
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
        return getLabels()
                .stream()
                .mapToDouble(this::getF1)
                .average()
                .orElse(Double.NaN);
    }

    /**
     * Get average (unweighted) accuracy.
     *
     * @return
     */
    synchronized double getAccuracy() {
        double nTruePositives = getTruePositives();
        return nTruePositives / getTotal();
    }

}
