package me.cortex.voxy.client.core.picking;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/** Worker-only, hierarchical CPU picking. Shapes are voxel approximations, even at LOD0. */
public final class LodRaycaster {
    public enum Status { HIT, MISS, INCOMPLETE, CANCELLED, BUDGET, INVALID }
    public enum Material { AIR, SOLID, UNKNOWN }
    public record Cell(Material material, int lod) {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public record Ray(Vec3 origin, Vec3 direction, double distance, Bounds bounds) {}
    public record Result(Status status, Vec3 position, Direction normal, int lod, long elapsedNanos,
                         int sectionReads, int visitedNodes) {}

    public interface Source extends AutoCloseable {
        /** Loads at most five local section snapshots for this 32-block region. */
        void prepare(int sectionX, int sectionY, int sectionZ);
        /** Finest informative section in the prepared region (0..4); 5 means unknown. */
        int finestLevel();
        /** Optional constant cell for a completely known uniform finest section. */
        default Cell uniform() { return null; }
        /** First known fine cell wins, including known air. */
        Cell sample(int blockX, int blockY, int blockZ);
        int reads();
        @Override default void close() {}
    }

    public static final class LimitReached extends RuntimeException {}
    private record Interval(double entry, double exit, Direction normal) {}
    private record Node(int x, int y, int z, int level, Interval interval) {}
    private final Ray ray;
    private final Vec3 direction;
    private final Source source;
    private final BooleanSupplier cancelled;
    private final long start = System.nanoTime(), deadline;
    private final int maxNodes;
    private int nodes;
    private Result result;

    private LodRaycaster(Ray ray, Source source, BooleanSupplier cancelled, long budgetNanos, int maxNodes) {
        this.ray = ray;
        this.direction = ray.direction.normalize();
        this.source = source;
        this.cancelled = cancelled;
        this.deadline = this.start + budgetNanos;
        this.maxNodes = maxNodes;
    }

    public static Result trace(Ray ray, Source source, BooleanSupplier cancelled, long budgetNanos, int maxNodes) {
        if (!valid(ray) || budgetNanos <= 0 || maxNodes <= 0)
            return new Result(Status.INVALID, null, null, -1, 0, 0, 0);
        var tracer = new LodRaycaster(ray, source, cancelled, budgetNanos, maxNodes);
        try { tracer.run(); }
        catch (LimitReached limit) { tracer.finish(Status.BUDGET, null, null, -1); }
        return tracer.result;
    }

    private void run() {
        Bounds b = this.ray.bounds;
        Interval range = intersect(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, 0, this.ray.distance);
        if (range == null) { finish(Status.MISS, null, null, -1); return; }
        double t = range.entry;
        Direction normal = range.normal;
        while (t < range.exit && check()) {
            Vec3 p = this.ray.origin.add(this.direction.scale(t));
            int sx = section(p.x, this.direction.x), sy = section(p.y, this.direction.y), sz = section(p.z, this.direction.z);
            Interval segment = intersect(sx * 32.0, sy * 32.0, sz * 32.0,
                    (sx + 1) * 32.0, (sy + 1) * 32.0, (sz + 1) * 32.0, t, range.exit);
            if (segment == null || segment.exit <= t) throw new LimitReached();
            this.source.prepare(sx, sy, sz);
            if (!check()) break; // A blocking local read can exhaust the deadline.
            Cell constant = this.source.uniform();
            Interval clipped = new Interval(t, segment.exit, normal);
            if (constant != null) visitCell(constant, clipped);
            else if (this.source.finestLevel() > 4) finish(Status.INCOMPLETE, null, null, -1);
            else visit(new Node(sx * 32, sy * 32, sz * 32, 5, clipped));
            if (this.result != null) return;
            t = segment.exit;
            // Entry into the next section has the opposite face of this section's exit.
            normal = exitNormal(sx * 32.0, sy * 32.0, sz * 32.0, (sx + 1) * 32.0,
                    (sy + 1) * 32.0, (sz + 1) * 32.0, t);
        }
        if (this.result == null) finish(Status.MISS, null, null, -1);
    }

    private void visit(Node node) {
        if (!check()) return;
        if (node.level <= this.source.finestLevel()) {
            visitCell(this.source.sample(node.x, node.y, node.z), node.interval);
            return;
        }
        int level = node.level - 1, size = 1 << level;
        var children = new ArrayList<Node>(8);
        for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            int bx = node.x + x * size, by = node.y + y * size, bz = node.z + z * size;
            Interval interval = intersect(bx, by, bz, bx + size, by + size, bz + size,
                    node.interval.entry, node.interval.exit);
            if (interval != null && interval.exit > interval.entry) {
                if (interval.entry == node.interval.entry) interval = new Interval(interval.entry, interval.exit, node.interval.normal);
                children.add(new Node(bx, by, bz, level, interval));
            }
        }
        children.sort(Comparator.comparingDouble(child -> child.interval.entry));
        for (Node child : children) {
            visit(child);
            if (this.result != null) return;
        }
    }

    private void visitCell(Cell cell, Interval interval) {
        if (!check()) return; // Shape/mapping lookup can consume the remaining time budget.
        if (cell.material == Material.UNKNOWN) finish(Status.INCOMPLETE, null, null, -1);
        else if (cell.material == Material.SOLID)
            finish(Status.HIT, this.ray.origin.add(this.direction.scale(interval.entry)), interval.normal, cell.lod);
    }

    private boolean check() {
        if (this.cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            finish(Status.CANCELLED, null, null, -1); return false;
        }
        if (++this.nodes > this.maxNodes || System.nanoTime() >= this.deadline) {
            finish(Status.BUDGET, null, null, -1); return false;
        }
        return true;
    }

    private void finish(Status status, Vec3 position, Direction normal, int lod) {
        this.result = new Result(status, position, normal, lod, System.nanoTime() - this.start, this.source.reads(), this.nodes);
    }

    private Interval intersect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                               double from, double to) {
        double entry = from, exit = to;
        Direction normal = Direction.getNearest(-this.direction.x, -this.direction.y, -this.direction.z);
        double[] origins = {this.ray.origin.x, this.ray.origin.y, this.ray.origin.z};
        double[] dirs = {this.direction.x, this.direction.y, this.direction.z};
        double[] mins = {minX, minY, minZ}, maxs = {maxX, maxY, maxZ};
        Direction[] negative = {Direction.WEST, Direction.DOWN, Direction.NORTH};
        Direction[] positive = {Direction.EAST, Direction.UP, Direction.SOUTH};
        for (int axis = 0; axis < 3; axis++) {
            if (dirs[axis] == 0) {
                // Half-open cells give axis-aligned boundary rays a single owner.
                if (origins[axis] < mins[axis] || origins[axis] >= maxs[axis]) return null;
            } else {
                double a = (mins[axis] - origins[axis]) / dirs[axis];
                double b = (maxs[axis] - origins[axis]) / dirs[axis];
                double near = Math.min(a, b), far = Math.max(a, b);
                if (near >= entry) { entry = near; normal = dirs[axis] > 0 ? negative[axis] : positive[axis]; }
                exit = Math.min(exit, far);
                if (exit <= entry) return null;
            }
        }
        return new Interval(entry, exit, normal);
    }

    private Direction exitNormal(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double t) {
        double[] dirs = {this.direction.x, this.direction.y, this.direction.z};
        double[] origins = {this.ray.origin.x, this.ray.origin.y, this.ray.origin.z};
        double[] mins = {minX, minY, minZ}, maxs = {maxX, maxY, maxZ};
        Direction[] negative = {Direction.WEST, Direction.DOWN, Direction.NORTH};
        Direction[] positive = {Direction.EAST, Direction.UP, Direction.SOUTH};
        for (int axis = 0; axis < 3; axis++) if (dirs[axis] != 0) {
            double exit = ((dirs[axis] > 0 ? maxs[axis] : mins[axis]) - origins[axis]) / dirs[axis];
            if (Math.abs(exit - t) <= Math.ulp(t) * 4) return dirs[axis] > 0 ? negative[axis] : positive[axis];
        }
        return Direction.getNearest(-this.direction.x, -this.direction.y, -this.direction.z);
    }

    private static int section(double coordinate, double direction) {
        // Correct ownership when travelling negatively from an exact boundary.
        int section = (int) Math.floor(coordinate / 32.0);
        return direction < 0 && coordinate == section * 32.0 ? section - 1 : section;
    }

    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static boolean valid(Ray ray) {
        if (ray == null || !finite(ray.origin) || !finite(ray.direction) || !Double.isFinite(ray.distance)
                || ray.distance <= 0 || !Double.isFinite(ray.direction.lengthSqr()) || ray.direction.lengthSqr() < 1e-12
                || ray.bounds == null) return false;
        Bounds b = ray.bounds;
        return Double.isFinite(b.minX) && Double.isFinite(b.maxX) && Double.isFinite(b.minY) && Double.isFinite(b.maxY)
                && Double.isFinite(b.minZ) && Double.isFinite(b.maxZ) && b.minX < b.maxX && b.minY < b.maxY && b.minZ < b.maxZ
                && b.minX >= -30_000_000 && b.maxX <= 30_000_000 && b.minZ >= -30_000_000 && b.maxZ <= 30_000_000
                && b.minY >= -65536 && b.maxY <= 65536;
    }
}
