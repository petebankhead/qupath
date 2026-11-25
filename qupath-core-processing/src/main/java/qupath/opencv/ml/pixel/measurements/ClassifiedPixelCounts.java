package qupath.opencv.ml.pixel.measurements;

import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ClassifiedPixelCounts {

    public static final ClassifiedPixelCounts EMPTY = create(Map.of(), 0L);

    private final Map<PathClass, Long> counts;
    private final long totalCount;

    private ClassifiedPixelCounts(Map<PathClass, Long> counts, long totalCount) {
        this.counts = Map.copyOf(counts);
        this.totalCount = totalCount;
    }

    public static ClassifiedPixelCounts create(Map<PathClass, Long> counts, long totalCount) {
        return new ClassifiedPixelCounts(counts, totalCount);
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getCountsForSetContains(String name) {
        long total = 0L;
        for (var entry : counts.entrySet()) {
            if (PathClassTools.containsName(entry.getKey(), name))
                total += entry.getValue();
        }
        return total;
    }

    public long getCountsForUnclassified() {
        return counts.getOrDefault(PathClass.NULL_CLASS, 0L);
    }

    public long getCountsForExactClass(PathClass pathClass) {
        return counts.getOrDefault(pathClass, 0L);
    }

    public long getCountsForClassSetMatches(Set<String> names) {
        Objects.requireNonNull(names);
        long total = 0L;
        for (var entry : counts.entrySet()) {
            if (names.equals(entry.getKey().toSet()))
                total += entry.getValue();
        }
        return total;
    }


    public static ClassifiedPixelCounts sum(Collection<? extends ClassifiedPixelCounts> counts) {
        return sum(counts.toArray(ClassifiedPixelCounts[]::new));
    }

    public static ClassifiedPixelCounts sum(ClassifiedPixelCounts... counts) {
        if (counts.length == 0)
            return ClassifiedPixelCounts.EMPTY;
        else if (counts.length == 1) {
            return counts[0];
        }
        var map = new LinkedHashMap<PathClass, Long>();
        long total = 0L;
        for (var otherCounts : counts) {
            for (var entry : otherCounts.counts.entrySet()) {
                map.compute(entry.getKey(), (_, c) -> c == null ? entry.getValue() : c + entry.getValue());
                total += otherCounts.getTotalCount();
            }
        }
        return new ClassifiedPixelCounts(map, total);
    }

}
