// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import com.sun.management.HotSpotDiagnosticMXBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;

/**
 * The instance's Arrow memory: one root allocator capped at the instance's Arrow budget, from which
 * every run will take a capped child allocator of its own, so that a run going over its cap fails
 * alone.
 *
 * <p>Two checks make the service refuse to start rather than fail later:
 * <ul>
 *   <li><b>Arrow works in this JVM.</b> On Java 25 Arrow needs JVM flags (see this module's
 *       build.gradle), and without them it fails when it first uses memory, not when its classes
 *       load. So the check writes and reads back a small IPC stream instead of looking for a
 *       flag.</li>
 *   <li><b>The budget fits under the JVM's direct-memory limit.</b> Arrow memory is direct memory,
 *       which the JVM caps as a whole ({@code -XX:MaxDirectMemorySize}, by default the heap size)
 *       and shares with the Pulsar client's buffers. If that limit were hit before the budget, the
 *       error would be an {@code OutOfMemoryError} and {@code -XX:+ExitOnOutOfMemoryError} would end
 *       the whole instance, every run on it included — the opposite of failing one run. So:
 *       budget + Pulsar client memory limit + headroom must stay below the limit.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ArrowMemoryConfig {

    @Bean(destroyMethod = "close")
    BufferAllocator rootAllocator(
            @Value("${datahub.flow.arrow.budget:1GB}") DataSize budget,
            @Value("${datahub.pulsar.memory-limit-mb:512}") long pulsarMemoryLimitMb,
            @Value("${datahub.flow.arrow.headroom:128MB}") DataSize headroom) {
        long maxDirect = maxDirectMemory();
        long needed = budget.toBytes() + DataSize.ofMegabytes(pulsarMemoryLimitMb).toBytes()
                + headroom.toBytes();
        if (needed >= maxDirect) {
            throw new IllegalStateException("The Arrow budget does not fit in direct memory: budget "
                    + budget.toMegabytes() + " MB + Pulsar client memory limit " + pulsarMemoryLimitMb
                    + " MB + headroom " + headroom.toMegabytes() + " MB must stay below "
                    + "-XX:MaxDirectMemorySize (" + maxDirect / (1024 * 1024) + " MB). Raise "
                    + "MaxDirectMemorySize or lower datahub.flow.arrow.budget.");
        }

        RootAllocator allocator = new RootAllocator(budget.toBytes());
        try {
            probe(allocator);
        } catch (Exception | LinkageError e) {
            allocator.close();
            throw new IllegalStateException("Arrow does not work in this JVM. Start it with the flags "
                    + "in datahub-flow's build.gradle (arrowJvmFlags).", e);
        }
        log.info("Arrow memory ready: budget {} MB of {} MB direct memory", budget.toMegabytes(),
                maxDirect / (1024 * 1024));
        return allocator;
    }

    /**
     * Writes a small batch as an Arrow IPC stream and reads it back: what a run does with every
     * series it reads and writes. Allocating a buffer alone is not enough to prove Arrow works —
     * the IPC path wraps its memory in NIO buffers, which is where a missing JVM flag shows.
     */
    private static void probe(BufferAllocator allocator) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Float8Vector values = new Float8Vector("value", allocator);
             VectorSchemaRoot root = VectorSchemaRoot.of(values);
             ArrowStreamWriter writer = new ArrowStreamWriter(root, null, bytes)) {
            values.allocateNew(2);
            values.set(0, 1.5);
            values.set(1, -2.25);
            root.setRowCount(2);
            writer.start();
            writer.writeBatch();
            writer.end();
        }
        try (ArrowStreamReader reader = new ArrowStreamReader(
                new ByteArrayInputStream(bytes.toByteArray()), allocator)) {
            if (!reader.loadNextBatch()) {
                throw new IllegalStateException("Arrow wrote a stream it could not read back.");
            }
            Float8Vector values = (Float8Vector) reader.getVectorSchemaRoot().getVector("value");
            if (values.get(0) != 1.5 || values.get(1) != -2.25) {
                throw new IllegalStateException("Arrow read back different values than it wrote.");
            }
        }
    }

    /** {@code -XX:MaxDirectMemorySize}, or the heap's maximum when it is unset (the JVM's default). */
    private static long maxDirectMemory() {
        HotSpotDiagnosticMXBean hotspot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        long configured = Long.parseLong(hotspot.getVMOption("MaxDirectMemorySize").getValue());
        return configured > 0 ? configured : Runtime.getRuntime().maxMemory();
    }
}
