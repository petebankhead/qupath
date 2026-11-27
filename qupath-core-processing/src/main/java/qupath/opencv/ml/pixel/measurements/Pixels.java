package qupath.opencv.ml.pixel.measurements;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// TODO: REMOVE THIS IF IT ISN'T USED!
public interface Pixels {

    double getMeanForChannel(int channel);

    double getMaxForChannel(int channel);

    double getMinForChannel(int channel);

    long getTotalPixels();

    long getCountsForChannel(int channel);

    long getCountsForChannelCombination(int... channels);

}

record ChannelStats(double mean, double min, double max, long count, long total) {}

class PixelChannels implements Pixels {

    private final List<ChannelStats> stats;

    PixelChannels(List<ChannelStats> stats) {
        validateStats(stats);
        this.stats = List.copyOf(stats);
    }

    private static void validateStats(List<? extends ChannelStats> stats) {
        Objects.requireNonNull(stats, "Stats must not be null!");
        if (stats.isEmpty())
            return;
        long total = stats.getFirst().total();
        for (var s : stats) {
            if (s.total() != total) {
                throw new IllegalArgumentException("Channel stats have different totals!");
            }
            if (s.count() > s.total()) {
                throw new IllegalArgumentException("Channel count > channel total (" + s.count() + " > " + s.total() + ")");
            }
        }
    }

    public int nChannels() {
        return stats.size();
    }

    @Override
    public double getMeanForChannel(int channel) {
        return stats.get(channel).mean();
    }

    @Override
    public double getMaxForChannel(int channel) {
        return stats.get(channel).max();
    }

    @Override
    public double getMinForChannel(int channel) {
        return stats.get(channel).min();
    }

    @Override
    public long getTotalPixels() {
        return stats.isEmpty() ? 0L : stats.getFirst().total();
    }

    @Override
    public long getCountsForChannel(int channel) {
        return stats.get(channel).count();
    }

    @Override
    public long getCountsForChannelCombination(int... channels) {
        if (channels.length == 0)
            return 0L;
        else if (channels.length == 1)
            return getCountsForChannel(channels[0]);
        // TODO: Handle channel combinations!
        return 0;
    }

}

class ChannelCombinations {

    private final Map<Long, Long> combinations;

    ChannelCombinations(Map<Long, Long> combinations) {
        this.combinations = Map.copyOf(combinations);
    }

    public long getCountsForCombination(int channel) {
        long index = 1L << channel;
        return getCounts(index);
    }

    public long getCountsForCombination(int channel1, int channel2) {
        long index = (1L << channel1) | (1L << channel2);
        return getCounts(index);
    }

    public long getCountsForCombination(int channel1, int channel2, int channel3) {
        long index = (1L << channel1) | (1L << channel2) | (1L << channel3);
        return getCounts(index);
    }

    public long getCountsForCombination(int... channels) {
        return getCounts(getIndex(channels));
    }

    private long getCounts(Long index) {
        return combinations.getOrDefault(index, 0L);
    }

    public static long getIndex(int channel) {
        return 1L << channel;
    }

    public static long getIndex(int channel1, int channel2) {
        return (1L << channel1) | (1L << channel2);
    }

    public static long getIndex(int channel1, int channel2, int channel3) {
        return (1L << channel1) | (1L << channel2) | (1L << channel3);
    }

    public static long getIndex(int channel1, int channel2, int channel3, int channel4) {
        return (1L << channel1) | (1L << channel2) | (1L << channel3) | (1L << channel4);
    }

    public static long getIndex(int... channels) {
        long l = 0L;
        for (int c : channels) {
            l |= (1L << c);
        }
        return l;
    }

    public static long getIndex(int[] channels, int maxLength) {
        if (maxLength <= 0 || maxLength > channels.length) {
            throw new IllegalArgumentException("maxLength must be > 0 and <= channels.length");
        }
        long l = 0L;
        for (int i = 0; i < maxLength; i++) {
            l |= (1L << channels[i]);
        }
        return l;
    }

}