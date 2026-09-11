# Binary datapoint ingest: the wire format decision

Which binary format the datapoint ingest contract should use, ClickHouse Native or Apache
Arrow (ArrowStream), and what the surrounding design has to look like for either to pay
off. Companion to [SCALABILITY.md](SCALABILITY.md), which names the JSON contract as the
ceiling for the very high rate class and lists a binary-first contract as task 12.

Numbers described as measured are measured, with the method in section 3; everything else
is reasoning from the code and is marked as an estimate. Where a claim needs a real
partition or a load test to settle, it says so.

## Summary

- On the ClickHouse server the two formats are indistinguishable for our tables. Both
  decode three fixed-width columns into a column with a memory copy, both parse at under
  15 ns per row at sensible block sizes, and a full insert into the real table costs the
  same to within noise. The format used today, RowBinary, is four to five times the parse
  cost of either.
- The insert cost is dominated by the column codecs, not the input format. On float32 with
  Gorilla, LZ4, ZSTD(1) and ZSTD(3) all insert at about 100 ns per row; ZSTD(9) costs 35 to
  60 percent more for 8 percent less disk. ZSTD(3) is the trade to take.
- Block size matters more than format. A 100-row block costs about 10 µs of server-side
  pipeline work whichever format carries it. Frames should hold at least a thousand
  points; the SDKs batch to ten thousand.
- With Arrow agreed as the retrieval format, the SDKs carry Arrow anyway. What remains in
  Native's favour is 434 fewer bytes per frame, an exact 8-byte decimal, and a 100-line
  validator instead of a 400-line one. What remains in Arrow's favour is one interchange
  format for both directions, DataFrame round-trips, and any Arrow-capable tool being able
  to feed the endpoint. The decision is Arrow, with three conditions built into the design
  below.
- Wire compression is a separate layer from the format. zstd on the client at level 9 by
  default, applied per frame, mandatory, and carried compressed through the API and Pulsar
  to the consumer. It saves network bytes and API CPU; it does not change what ClickHouse
  does.

## 1. The path today

| Stage | Today | Cost class per point |
|---|---|---|
| SDK encode | string timestamp and string value per point, Jackson to a String body, no compression | 1 to 2 µs (estimate), 45 to 80 B on the wire |
| API parse and validate | Jackson parse, `parseLong`/`parseDouble` re-parse, `ZonedDateTime` twice per point, a linear scan plus a `HashSet` insert per point, then the value encoder (`TimeseriesService.prepareDatapointInsert`) | several µs (estimate) |
| API to Pulsar | `DataWrapperBin` via `Schema.AVRO`: one Avro record and one varint-prefixed `bytes` per point | 100 to 200 ns (estimate) |
| Consumer | Avro decode per point, then two `writeLong` and one `write(byte[])` per row into a piped stream (`ClickHouseDatapointService.writeBinaryRows`) | 100 to 200 ns (estimate) |
| ClickHouse | plain `RowBinary` over client-v2 by HTTP, one insert per value-type table, `async_insert=1` | 28 ns parse (measured), about 100 to 140 ns insert on float32 depending on codec (measured) |

Facts from the code that bind the design:

- Seven datapoint tables, one per value type, each `timeseries_id Int64`, `timestamp
  DateTime64(3, 'UTC')`, one value column. Only `datapoints_text` and `datapoints_mixed`
  carry String, LowCardinality or Nullable columns (`datahub-api/src/main/resources/db/clickhouse.sql`).
- Timeseries ids are Postgres identity values. A client cannot compute them; it resolves
  them once per series and caches.
- The ClickHouse client passes any format through as an opaque stream, `Native` and
  `ArrowStream` alike. Nothing in the repo uses Arrow today, and no module sets
  `--add-opens`.
- Three consumers of a datapoint message need the series external id, not only the id: the
  subscription fan-out, the browser live tail, and the latest-value cache. A frame
  therefore carries a series directory.

## 2. Anatomy of the two formats on our tables

### ClickHouse Native block (HTTP flavour)

`varuint columnCount`, `varuint rowCount`, then per column: `varuint len + name`,
`varuint len + type`, column data. Fixed-width columns are contiguous little-endian arrays.
`String` is per row `varuint len + UTF-8`. `Nullable(T)` is `rowCount` null-map bytes then
T's data for every row. `LowCardinality` has its own dictionary encoding; the wire would
carry plain `String` and the server casts (`input_format_native_allow_types_conversion=1`,
the default since 23.3). Measured header for our float table: 68 to 70 bytes.

This is the server's own in-memory column layout. The reader fills a fixed-width column
with one `readStrict` into the column's array.

### Arrow IPC stream

Schema message (FlatBuffer, 256 bytes for our three fields), then per record batch a
message with a `FieldNode(length, nullCount)` per field and a `Buffer(offset, length)` per
buffer (240 bytes), then the 8-byte-aligned body: validity bitmap (empty when there are no
nulls) and data buffer per field, then an 8-byte end-of-stream marker. Measured fixed cost:
504 bytes per single-batch stream, 240 bytes per additional batch.

On input ClickHouse copies the body into Arrow buffers, then converts each array into a
ClickHouse column: a memory copy for Int64 and Float64, a per-value loop for Timestamp, a
narrowing loop for Decimal128.

### Type mapping for the seven value types

| Value type | Table column | Native wire type | Arrow wire type | Server work on Arrow |
|---|---|---|---|---|
| bigint | `Int64` | `Int64`, 8 B | `Int64`, 8 B | memory copy |
| float | `Float64` | `Float64`, 8 B | `Float64`, 8 B | memory copy |
| float32 | `Float32` | `Float32`, 4 B | `Float32`, 4 B | memory copy |
| numeric | `Decimal(18, 6)` | scaled `Int64`, 8 B | `Decimal128(18, 6)`, 16 B | narrowing loop |
| decimal32 | `Decimal(9, 4)` | scaled `Int32`, 4 B | `Decimal128(9, 4)`, 16 B | narrowing loop |
| text | `LowCardinality(String)` | `String`, 1 to 2 B + bytes | `Utf8`, 4 B offset + bytes | copy, then the cast to LowCardinality (same cast as Native) |
| mixed | `Nullable(Float64)`, `LowCardinality(Nullable(String))` | null map 1 B + 8 B, null map 1 B + string | validity bit + 8 B, validity bit + 4 B offset + bytes | null maps from bitmaps, then the same cast |

The first two columns are always `timeseries_id Int64` and `timestamp DateTime64(3,
'UTC')`. In Arrow the timestamp is `Timestamp(MILLISECOND, "UTC")`; with that exact unit
and zone the server type equals the table type and no cast runs. Type-name spellings were
confirmed on the server: `Decimal(18, 6)`, `Decimal(9, 4)`, `DateTime64(3, 'UTC')`.

## 3. Measurements

Method: ClickHouse 26.5.1 (the compose image) and 26.8.2.7 (the latest release), each
run as a throwaway `clickhouse local` container on an AMD Ryzen 9 7950X desktop that was
in use for other work at the time. Ten million rows, generated sorted by (timeseries_id,
timestamp) with 100 series of 100k points at one-second spacing, values a sine plus noise
rounded to two or three decimals. Files written once, read from the page cache. Every
timing is a single thread (`max_threads=1`, `max_parsing_threads=1`, no parallel
parsing), best of repeated runs, and parse timings include a three-column aggregate over
the parsed data.

Noise: repeated reads of the same file on the same version varied by up to 3x between
consecutive runs on this shared machine. Parse timings under about 150 ms for ten million
rows are therefore at the noise floor; read them as "under 15 ns per row" rather than as
ranked values. Inserts at one to two seconds are stable to about 10 percent.

Compression ratios: the synthetic series repeat the same timestamp sequence and the same
base signal, so any codec with a window over about 800 KB finds cross-series repetition
that real data does not have. Ratios from LZ4 and zstd level 1, whose windows are smaller,
are the conservative figures; speeds are unaffected.

### 3.1 Bytes on the wire against batch size

| Rows | Native | ArrowStream | RowBinary | JSON rows (today's shape) |
|---|---|---|---|---|
| 10 | 308 | 744 | 240 | 439 |
| 1,000 | 24,069 | 24,504 | 24,000 | 44,785 |
| 100,000 | 2,400,070 | 2,400,504 | 2,400,000 | 4,535,187 |
| 10,000,000 in 100-row blocks | 246,800,000 | 264,000,264 | | |
| 10,000,000 in 10k-row blocks | 240,069,000 | 240,240,264 | | |

Per row all three binary formats are 24 bytes for float64 and 20 for float32. Native adds
68 to 70 bytes per block, Arrow 504 per stream plus 240 per extra batch. At 100-row blocks
that is 2.8 percent for Native and 10 percent for Arrow; at 10k rows both are under 0.1
percent. JSON is 45 bytes per row with short values and 64 with the id column, before any
collection framing.

### 3.2 Compressed

100k float64 rows, gzip level 6 as a neutral yardstick only: Native 590,279 and Arrow
590,271 (5.9 B per row), RowBinary 712,178 (7.1 B per row, interleaved rows compress
worse), JSON 627,720 (6.3 B per row). ClickHouse's default in-band Arrow compression
(`lz4_frame`) gave 987,768 (9.9 B per row).

Compressed, JSON is only 7 percent larger than binary. The case against JSON is CPU, not
bytes.

### 3.3 Server parse cost against format, block size and version

float64, 26.5, one sequential pass:

| Format and block size | Time for 10M rows | ns per row |
|---|---|---|
| Native, 1M-row blocks | 50 ms | 5.0 |
| Native, 10k-row blocks | 64 ms | 6.4 |
| Native, 100-row blocks | 1,348 to 1,674 ms | 135 to 167 |
| ArrowStream, 1M-row batches | 88 ms | 8.8 |
| ArrowStream, 10k-row batches | 57 ms | 5.7 |
| ArrowStream, 100-row batches | 685 to 1,169 ms | 68 to 117 |
| RowBinary (today) | 277 ms | 27.7 |
| JSONEachRow, ClickHouse's own parser | 2,126 ms | 213 |

float32, the same files read by both versions, best of four reads each:

| Format and block size | 26.5 | 26.8.2.7 |
|---|---|---|
| Native, 1M-row blocks | 118 ms | 37 ms |
| ArrowStream, 1M-row batches | 69 ms | 148 ms |
| Native, 10k-row blocks | 34 ms | 73 ms |
| ArrowStream, 10k-row batches | 129 ms | 39 ms |
| Native, 100-row blocks | | 1,126 to 1,421 ms |
| ArrowStream, 100-row batches | | 443 to 885 ms |

The float32 table is the noise floor made visible: the ordering between Native and Arrow
and between the two versions flips from row to row, with every value between 3 and 15 ns
per row. There is no version regression and no format winner at 10k-row frames or above.
Block size is the only effect that survives the noise: a 100-row block costs roughly 10
µs of per-block pipeline work whichever format carries it, which is 100 ns per point.
RowBinary, the format used today, is 4 to 5 times the cost of either columnar format, and
ClickHouse's own JSON parser is 40 times, with Jackson on the API slower still.

### 3.4 Decimal

| | File size for 10M rows | ns per row, 26.5 | 26.8.2.7 |
|---|---|---|---|
| Native `Decimal(18, 6)` as scaled Int64 | 240,000,770 | 6.6 | 7.8 |
| Arrow `Decimal128(18, 6)` | 320,002,672 | 13.7 | 11.0 |

Arrow's smallest decimal ClickHouse's bundled reader accepts is 128-bit: 8 bytes and a few
ns per row more on the two decimal table types. Numeric and decimal32 series are a
minority of sensor data, and the wire penalty compresses away (the high 8 bytes are zero).

### 3.5 Insert into the real table, float64, ZSTD(9), 26.5

`datapoints_float` as defined in `clickhouse.sql`: `ReplacingMergeTree`, `ORDER BY
(timeseries_id, timestamp)`, codecs `DoubleDelta, ZSTD(9)` on id and timestamp and
`Gorilla, ZSTD(9)` on value.

| Input format | Insert time | ns per row | Bytes on disk per row |
|---|---|---|---|
| Native | 2,513 ms | 251 | 5.65 |
| ArrowStream | 2,457 ms | 246 | 5.66 |
| RowBinary | 2,379 ms | 238 | 5.66 |
| Native with ZSTD(1) instead of ZSTD(9) | 998 ms | 100 | 7.27 |

The input format is invisible inside the insert: the three runs are within 5 percent and
not in the order the parse numbers predict.

### 3.6 Insert into the float32 table across second-stage codecs

`datapoints_float32` with the second-stage codec varied on all three columns:
`DoubleDelta, X` on id and timestamp, `Gorilla, X` on value. Ten million float32 rows, one
thread. The scan is `count(), sum(value)` over the whole table on one thread, which is
decompression plus a trivial aggregate.

| Codec X | Native insert, 26.5 | Arrow insert, 26.5 | Native, 26.8.2.7 | Arrow, 26.8.2.7 | Bytes on disk per row | Value column per row | Scan, 26.5 |
|---|---|---|---|---|---|---|---|
| LZ4 | 975 ms | 988 ms | 1,008 ms | 1,030 ms | 3.40 | 3.38 | 170 ms |
| LZ4HC | 1,447 ms | 1,472 ms | | | 3.02 | 3.00 | 104 ms |
| ZSTD(1) | 1,016 ms | 1,020 ms | 955 ms | 1,012 ms | 2.98 | 2.96 | 162 ms |
| ZSTD(3) | 1,025 ms | 1,081 ms | 1,119 ms | 1,326 ms | 2.83 | 2.81 | 168 ms |
| ZSTD(9) | 1,374 ms | 1,590 ms | 1,601 ms | 1,720 ms | 2.61 | 2.59 | 155 ms |

Bytes on disk were identical on both versions to the third digit.

Findings:

- DoubleDelta reduces the id and timestamp columns to under 0.02 bytes per row each on
  regularly sampled data. The value column is the disk footprint; Gorilla on this signal
  leaves 3.4 bytes of the 4, and the second-stage codec does the rest.
- LZ4, ZSTD(1) and ZSTD(3) cost about the same to insert, roughly 100 ns per row. ZSTD(3)
  is 17 percent smaller than LZ4 for that price. ZSTD(9) buys another 8 percent for 35 to
  60 percent more CPU. LZ4HC is the worst trade: ZSTD(9)'s CPU for ZSTD(1)'s size, its
  only merit the fastest scan.
- Native and Arrow input agree within 5 to 10 percent on every codec on both versions.
- The two schema copies have drifted. This repository's `clickhouse.sql` declares ZSTD(9);
  the tenant manager's own copy, which provisions every production tenant, declares
  `CODEC(T64, LZ4)` and `CODEC(Gorilla, LZ4)`. Production is therefore on the LZ4 row of
  the table above.
- Recommendation for the table codecs, separate from the format decision: ZSTD(3) in both
  copies, confirmed on a real partition with the `system.events` check in SCALABILITY.md
  task 8 first. For production that is 17 percent less disk at the same insert CPU as
  LZ4; for dev it is 35 percent less CPU than ZSTD(9). ClickHouse has no runtime migration
  in either repository, so existing tenants get it through an `ALTER TABLE ... MODIFY
  COLUMN ... CODEC` run by the tenant manager, effective on new parts and merges.

### 3.7 Client-side wire compression

The format and the wire compression are separate layers. Over HTTP the sender compresses
the body and declares it; the consumer's ClickHouse client does that with zstd today, and
the native TCP protocol does the same per block with LZ4. The server decompresses back to
the same 20 or 24 bytes per row before it parses, so nothing in sections 3.3 to 3.6
changes with compression on. The column codecs run when the server writes parts and
cannot be applied by a client. Only pre-built parts attached with `clickhouse local`
would move that work to the client, which is a bulk-import tool path, not an ingest API.

Measured on the 200 MB float32 Native stream, one thread, through ClickHouse's own stream
codecs (what the server and its client run) and through the zstd command-line tool (the
in-memory ceiling):

| Method | Compress, CH 26.5 | Compress, CH 26.8.2.7 | Compress, zstd tool | Decompress, CH 26.8.2.7 | Decompress, zstd tool | Bytes per row on the wire |
|---|---|---|---|---|---|---|
| none | | | | | | 20.0 |
| LZ4 frame | 193 MB/s | 207 MB/s | | 2.6 GB/s | | 7.9 |
| zstd level 1 | 727 MB/s | 1.2 GB/s | 636 MB/s | 1.4 GB/s | 1.6 GB/s | 4.8 |
| zstd level 3 | 483 MB/s | 525 MB/s | 570 MB/s | above 1.5 GB/s | 2.7 GB/s | 2.3, inflated by the synthetic repetition |
| zstd level 9 | | 81 MB/s | 71 MB/s | 1.6 GB/s | 2.2 GB/s | 1.8, same caveat |

Per point at 20 bytes: zstd level 1 costs 20 to 30 ns to compress and about 15 ns to
decompress; level 3 about 40 and 15; level 9 about 250 to compress and 13 to decompress.
Decompression is the same at every level, so the level is purely the sender's choice.

The SDKs default to level 9: the client pays for it. What that means per million points:
a quarter second of CPU on a desktop core, a few seconds on a small edge CPU, for a wire
size that on real data will be perhaps 20 to 30 percent below level 3 (the synthetic 2x
in the table is inflated by cross-series repetition). A level 9 context needs about 30 MB
of memory, so the SDK compresses frames in parallel where memory allows and sequentially
where it does not.

Compress once, decompress once: the client compresses each frame independently and the
envelope records it (section 10). Compression is mandatory: a frame that declares none is
refused before its payload is read, so an uncompressed binary stream never reaches the
API. The API decompresses a frame only to validate it and forwards the client's compressed
bytes to Pulsar untouched; the block topic's producer therefore runs with compression off,
and the consumer decompresses once, at about 15 ns per point, before the merge. Compared
with body-level `Content-Encoding`, this removes the API's own compression pass, keeps
Pulsar storage at level-9 size, lets a ten-frame request compress on ten cores, and makes
each frame a self-contained unit that the consumer can decompress in parallel.

Where compression pays and where it does not:

- SDK to API: yes. Edge links are the narrow pipe. zstd on every SDK; levels 1, 3 and 9
  selectable, default 9.
- Pulsar: frames arrive compressed and stay compressed, about 2 to 4 bytes per point in
  BookKeeper. Arrow's in-band buffer compression stays off so this pass is the only one.
- Consumer to ClickHouse: it saves bytes on a link that is not the bottleneck. At 10M
  points per second the raw stream is 200 MB/s, a fifth of a 10 Gbit link. The client's
  zstd stays at level 1; it costs 30 ns per point and changes nothing on the ClickHouse
  side.

### 3.8 Cross-check against fastformats.clickhouse.com

The public FastFormats runs insert ClickBench "hits" (about 105 columns, many strings) on
ClickHouse Cloud with object storage. Their per-run results show the same shape: for
Native.lz4 at 1M rows, 30.7 s of the 43.8 s is part writing, 1.2 s network, 0.6 s sorting.
The format-dependent remainder is 8 to 11 s for both Native and ArrowStream, and their
ordering flips with compression placement (ArrowStream.zstd used in-band buffer
compression, Native.lz4 HTTP compression). RowBinary is the clear loser there too: 57 s and
1.6 GiB versus 0.6 GiB of memory, because the server parses each field of each row. On our
three fixed-width columns the gap is smaller in absolute terms but the same in kind.

### 3.9 End to end on the real platform, 100 million points

Everything above measures ClickHouse alone. This measures the platform: the Java SDK against a
running api, Pulsar, the stateless consumer and ClickHouse, sending the same 100 million float32
points down each path to its own 100 series. It is `datahub-e2e`'s `benchmark` task, so it can be
re-run; `datahub-e2e/README.md` has the setup. Wire bytes are counted by a TCP relay the SDK is
pointed at, so they include headers and are not inferred from the payload. The api and the consumer
each ran from a jar with a 4 GB heap, never `bootRun`, which would have disabled C2.

| | JSON | binary | |
|---|---|---|---|
| Ingest wall time | 52.0 s | 30.4 s | 1.7x |
| Points per second | 1,922,646 | 3,286,618 | 1.7x |
| Settle to readable | 2.1 s | 1.1 s | |
| Bytes on the wire | 5.00 GB | 346 MB | **14.4x** |
| Bytes per point | 49.98 | 3.46 | |
| Latency mean, per 1M-point call | 461 ms | 245 ms | |
| Latency p99 | 618 ms | 345 ms | |
| **api CPU** | **287.8 s** | **6.3 s** | **46x** |
| Consumer CPU | 64.2 s | 6.4 s | 10x |
| api peak RSS | 3.8 GB | 5.2 GB | |
| Client heap growth | 1.39 GB | 1.91 GB | |

The headline is not throughput, it is the api's CPU: 287 seconds against 6. Accepting a hundred
million points cost the JSON path most of five minutes of a core, and the binary path six seconds,
because the api parses and re-parses every value on one path and validates a frame and forwards its
bytes on the other. Wall time improves by less than that ratio because the client is doing more
work and, at these rates, is itself the limit.

Both costs are real and land on the client: the binary path used 37 percent more client heap
(building Arrow buffers and compressing them) and pushed the api's resident set higher, since a
64 MiB body is held while it is validated. Neither is a surprise and both were the trade the design
made deliberately.

Verified independently of the harness: 208 million rows in `datapoints_float32` afterwards
(200 million from the two runs plus earlier calibration), 558 MiB on disk, about 2.8 bytes per row,
which matches the ZSTD(9) column-codec figure in 3.6 for a signal of this shape.

One caveat worth repeating. The generated series is a slow sine plus noise, and each of the 100
series gets its own. An earlier version gave every series identical values, and zstd found the
repetition across them and reported 0.52 bytes per point, seven times better than the honest
figure. Compression numbers are only as good as the signal behind them.

## 4. Client side: what moves to the SDK, and the DataFrame case

The goal is to do the heavy work in the SDK and leave datahub-api and ClickHouse as little
as possible. This is what can move and what cannot, per point:

| Work | Where today | Where in the binary path | Cost per point (estimate unless marked) |
|---|---|---|---|
| Parse timestamps and values from text | API | SDK, or nowhere when the input is typed | 1 to 2 µs saved on the API |
| Resolve external id to internal id and value type | API, one Postgres query per collection | SDK cache, one bulk REST call per unseen series | 0 after the first call |
| Type check per value | API | SDK against the cached series type | 0 on the API |
| Sort by (id, timestamp), drop duplicate pairs | nowhere (ClickHouse sorts, ReplacingMergeTree merges) | SDK | 50 to 100 ns for a 100k-row sort in Rust; 2 ns to verify an already-sorted input |
| Group by value type, cut frames, pack requests | API per collection | SDK | memory copy |
| Compress the wire | nowhere | SDK, zstd level 9 by default | about 250 ns (measured) |
| Authorize and verify the series exist and have the claimed type | API | API, one cached lookup per distinct series | 2 to 4 ns per row plus per-series lookups |
| Publish to Pulsar | API | API, forwarding the compressed frame | under 10 ns |
| Coalesce frames into large blocks | consumer | consumer, column concatenation | 2 ns memory copy |
| Column codecs, part writing, merges | ClickHouse | ClickHouse | 100 ns at ZSTD(3) (measured); not movable |

The API's residual job is to verify and forward bytes: a few nanoseconds per row and one
cached metadata lookup per distinct series. ClickHouse keeps its 100 ns per row whatever
the client does; sorted, de-duplicated, large frames are the only levers it responds to,
and the SDK controls all three.

### A DataFrame already in Arrow, through the Python SDK

The Python SDK is a binding over the Rust core. A polars, pandas or pyarrow frame reaches
Rust through the Arrow PyCapsule interface with no copy. In Rust the work is the same
whichever wire format follows: map external ids to internal ids from the cache and build
the id column, cast the timestamp to millisecond UTC if it is not already, cast the value
to the series' type, sort by (id, timestamp), split by value type. After that, writing
Arrow IPC is a direct write of the existing buffers, and writing Native would also be a
direct write of the same buffers, since both are raw little-endian arrays. If the frame
already has the canonical schema and order, both are zero-copy, and the measurable
difference is 504 versus 70 bytes per frame. Arrow is the more convenient path because the
input already is Arrow and no second layout has to exist, not a faster one.

### Footprint by language

| Java option | Jars added to the SDK | JVM flags for every SDK user |
|---|---|---|
| Native writer, hand-rolled, about 150 lines | none | none |
| Minimal Arrow IPC codec on the FlatBuffers classes (`arrow-format` 122 KB, `flatbuffers-java` 111 KB), about 400 lines | 0.23 MB, plus `zstd-jni` 6.4 MB for the wire either way | none |
| Full arrow-java 19.0.0 with the Netty allocator | about 6.5 MB: arrow-vector 2.24 MB, arrow-memory-core 0.12, arrow-memory-netty 0.01 plus buffer-patch 0.04, netty-buffer 0.34, netty-common 0.72, arrow-format 0.12, flatbuffers 0.11, a second Jackson (2.x databind 1.66, core 0.60, annotations 0.08, jsr310 about 0.13), commons-codec about 0.37 | `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED` always; `--enable-native-access=io.netty.common` on Java 25 with Netty |

The Java SDK's current runtime classpath is about 3.1 MB. Full arrow-java triples it and
adds two mandatory JVM flags, which for Spring Boot users means `JAVA_TOOL_OPTIONS` or
launcher changes; the SDK requires Java 25, so the native-access flag applies. The
minimal codec reads our fixed response schema into `long[]` and `double[]` without an
allocator, so the core SDK can speak Arrow both ways with no flags, and full arrow-java
becomes an optional module for users who want `VectorSchemaRoot`.

Rust: about ten crates (`arrow-ipc`, `arrow-array`, `arrow-buffer`, `arrow-data`,
`arrow-schema`, `flatbuffers` and their leaves) give writer and reader. Python: the wheel
already depends on numpy; Arrow results go out through the PyCapsule interface so pyarrow
(50 MB for a manylinux wheel) is never a dependency of ours.

## 5. API side: validation and memory

The API parses untrusted bytes and must prove every frame good before publishing
(CONSTRAINTS.md, validate before it goes async). This is the largest remaining difference
between the formats.

| | Native | Arrow, full arrow-java | Arrow, minimal codec |
|---|---|---|---|
| Reader size | about 100 lines, fixed layout | library | about 400 lines including subset policing |
| Memory | on-heap `byte[]` body, `LongBuffer` views | off-heap allocator; a 64 MiB body is 64 MiB of direct memory per in-flight request; needs `--add-opens` in the API and its launch configs | on-heap, views over the body |
| Per-row cost | 1 to 2 ns for sortedness, runs and latest timestamp | same scan behind vector accessors | same scan over buffer offsets |
| Per-frame cost | under 1 µs | 10 to 50 µs (message parsing, allocations) | 2 to 5 µs (two FlatBuffers) |
| Attack surface | bounds checks on a fixed layout | FlatBuffers verifier is not on by default in Java | hand-verified offsets, explicit rejection list |

Full arrow-java in the API is the option to avoid. The minimal codec must reject: legacy
framing without the continuation marker, more than one schema, any schema difference
(field count, names, types including timestamp unit and zone, nullability), any dictionary
batch in v1, body compression (compression belongs to the frame envelope), non-zero null
counts on non-nullable fields, buffers outside the body or unaligned, non-monotonic Utf8
offsets, a missing end-of-stream marker, trailing bytes, and any row total that disagrees
with the envelope.

## 6. Request sizing: one million points per request

One million float32 points is 20 MB raw and roughly 3 to 4 MB after zstd level 9 on real
data. The request carries ten frames of 100k points, each 2.0 MB raw and compressed on its
own, so every frame stays under the Pulsar broker's 5 MiB message limit with the envelope
and directory included, and no chunking or broker change is needed.

Budget for such a request from a Rust or Python client on a LAN, estimates from the
measured rates:

| Step | Time | Whose CPU |
|---|---|---|
| Sort 1M rows, or verify an already-sorted input | 50 to 100 ms, or 2 ms | client |
| Build ten frames | 5 ms | client |
| zstd level 9 on ten 2 MB frames | 250 ms of CPU, 30 to 60 ms wall on ten cores | client |
| Upload 3 to 4 MB | 3 ms on 10 Gbit, 300 ms on 100 Mbit | |
| Decompress ten frames on the API to validate | 15 ms | API |
| Validate ten frames, one cached lookup per distinct series | 3 to 5 ms | API |
| Forward the ten compressed frames to Pulsar, no recompression | 10 to 20 ms | API |
| Response | about 50 ms after the upload completes | |
| Consumer batch window, decompress, merge, insert 1M rows | 500 ms window plus 15 ms plus 100 ms insert | consumer, ClickHouse |

API CPU per million points is about 25 ms; ClickHouse CPU about 100 ms. The endpoint's own
limits: 64 MiB decompressed per request (about 3M numeric points), 32 frames per request,
4 MiB per frame decompressed, and an in-flight limiter per API instance answering 429 with
`Retry-After` beyond it, which the SDKs already retry. Quotas charge decompressed bytes.

## 7. Pulsar: keep it, and give the binary stream its own topic

The reason for Pulsar was batching: many small requests become one bulk insert. The
question is whether the binary path, whose frames are already large, still needs it.

Cost of the Pulsar leg per point, from the measured rates, with frames carried compressed:
the API forwards bytes it already holds, the consumer decompresses at about 15 ns, merges
at 2 ns and compresses to ClickHouse with zstd level 1 at 30 ns. Roughly 50 ns of CPU on
the consumer against about 30 ns for a direct API-to-ClickHouse insert with zstd. The
difference, 20 ns per point, is 0.2 cores at 10M points per second and 2 at 100M, against
10 for ClickHouse's own insert at that rate.

What those 20 ns buy:

- A durable acknowledgement that does not depend on ClickHouse being up or fast. A direct
  insert makes the client's 2xx wait for the part write and fail when ClickHouse is
  restarting or merging heavily.
- The burst buffer: Pulsar absorbs a spike at the accept rate while ClickHouse drains at
  its own rate, bounded by the backlog quota.
- Batching that actually reaches ClickHouse as large blocks. The consumer merges every
  frame in a 20 MB or 500 ms batch per tenant and table into blocks of up to 1M rows by
  column concatenation, so the 10 µs per-block cost from section 3.3 never applies, no
  matter how small the individual requests were. ClickHouse's own `async_insert` also
  coalesces, but every small request still costs the server an HTTP request and the
  acknowledgement waits for the flush.
- One place for the WebSocket fan-out and the live tail to hang off.

The binary stream gets its own topic, `datapoint-blocks/all-datapoint-blocks`, in a
namespace of its own next to `datapoints/all-datapoints`. The existing topic is
schema-typed Avro and its consumer decodes typed objects, so raw frames there would need
schema negotiation for no gain. A topic of its own gives the binary stream a bytes schema,
producer compression off because frames arrive compressed, its own partition count (16 to
start, raise-only like the other), its own Shared subscription and dead-letter topic, and
consumers that scale independently of the Avro listener. A namespace of its own gives it a
backlog quota and retention sized for a compressed stream, and a partitioned
auto-topic-creation policy that closes the race in SCALABILITY.md task 9 for this topic
from the start. The tenant manager owns namespaces and their policies; the API creates the
topic at startup, as it does for `all-datapoints`.

The one consequence: a series can have points in flight on both topics with no ordering
between them. That changes nothing, because Shared subscriptions never ordered them and
ReplacingMergeTree resolves duplicates on (id, timestamp).

## 8. Endpoint, code path and SDK surface

Separate endpoint, separate code path: `POST /timeseries/data/binary`, its own controller
and service, its own cap and limiter, nothing shared with the JSON handler except the
latest-value cache, the quota service and the security beans. The JSON path stays as it
is.

Each SDK exposes the binary path as its own method, not an option on the JSON call:
an immediate `ingestBinary` for prepared batches, and a closeable `BinaryIngestBuffer` for
small inserts that accumulates points and flushes at 10k points or 200 ms, whichever
first. Options: zstd level 1, 3 or 9 (default 9, never off), frame and request caps,
parallel or sequential frame compression, fail-fast. The buffered mode is how many single
datapoint requests become one request without touching the API: the SDK batches first,
the consumer merges second.

## 9. Decision

| Criterion | Native | Arrow | Weight |
|---|---|---|---|
| Server parse at 10k-row frames or above | 3 to 12 ns | 4 to 15 ns | none, noise floor on both versions |
| Server insert, float32 ZSTD(3) | 103 to 112 ns | 108 to 133 ns | none |
| Wire bytes per numeric point | 20 or 24 | 20 or 24 | none |
| Fixed bytes per frame | 70 | 504 | low, SDK batches 10k |
| Decimal tables | 8 B, 7 ns | 16 B, 11 to 14 ns | low, minority types, compresses away |
| SDK dependencies | none | Arrow libs already in for reads, or the 0.23 MB minimal codec | withdrawn |
| API validator | 100 lines, fixed layout | 400 lines, subset policing | medium |
| Third-party producers | need our spec | any Arrow producer plus id resolution and sorting | medium |
| Symmetry with the read path | translate | same schema objects both ways, DataFrame round-trip | high |
| Dependence on ClickHouse internals | the server's own format, stable a decade | bundled Arrow C++ reader, mature for primitives | low |
| Spec to document for Rust and Python | one page of byte layout | "an IPC stream with this schema" plus the rejection list | low |

Arrow IPC for ingest as well as retrieval, on three conditions:

1. Frames are at least 1,000 points and the SDKs batch to 10,000 by default, in the
   buffered mode as well. Below that the server-side per-block cost, not the format,
   dominates.
2. The API and the consumer parse frames with the minimal fixed-schema IPC codec on
   `arrow-format` plus `flatbuffers-java`, on-heap, with no JVM flags. Full arrow-java
   never enters the API. The Java SDK uses the same codec by default for both directions,
   with full arrow-java as an optional module.
3. Decimal series travel as `Decimal128`; the measured 8 bytes and a few ns per row on
   those two table types are accepted.

What Native would have bought, for the record: 434 fewer bytes per frame, no Decimal
penalty, a 100-line validator instead of 400, and no dependence on ClickHouse's bundled
Arrow reader. Against one interchange format across the platform, DataFrame round-trips,
and any Arrow-capable tool being able to feed the endpoint, those are the smaller wins. The
envelope keeps a codec byte so a Native payload can be added later without a new media
type if a measurement ever says otherwise.

Independent of the format, two changes are worth more than the format choice: table codecs
to ZSTD(3) after the `system.events` check (section 3.6), and per-frame zstd on the SDK
to API leg, carried compressed to the consumer (section 3.7).

## 10. Wire format v1

Media type `application/vnd.intellistream.datapoint-block`. A body is one or more frames
back to back, with no body-level `Content-Encoding` (any is 415): compression lives inside
each frame so the frame can travel compressed end to end.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `DHDP` |
| 4 | 1 | version = 1 |
| 5 | 1 | valueTypeId (1 bigint, 2 float, 3 numeric, 4 text, 5 decimal32, 6 mixed, 7 float32) |
| 6 | 1 | codec: 1 = Arrow IPC stream (v1); 0 reserved for a ClickHouse Native block |
| 7 | 1 | compression: 1 = zstd, required; any level, the level is the sender's business. 0 is rejected: an uncompressed stream never reaches the API |
| 8 | 4 | u32 LE rowCount, at least 1 |
| 12 | 4 | u32 LE seriesCount, distinct ids in the payload |
| 16 | 4 | u32 LE directoryByteLength |
| 20 | 4 | u32 LE payloadByteLength, as carried |
| 24 | 4 | u32 LE rawPayloadByteLength, after decompression |
| 28 | dir | series directory: seriesCount times { Int64 LE id, varuint len, UTF-8 externalId }, ids strictly ascending and equal to the set of ids in the payload |
| 28 + dir | payload | one Arrow IPC stream (one schema message, one or more record batches, end-of-stream), zstd-compressed as a single zstd frame |

The directory stays uncompressed so the API can read the series without touching the
payload. The payload is decompressed into exactly `rawPayloadByteLength` bytes; a stream
that ends early, runs long, or exceeds the 4 MiB raw cap is rejected, which is also the
zstd-bomb guard.

Canonical Arrow schema per value type: `timeseries_id: Int64 not null`, `timestamp:
Timestamp(MILLISECOND, "UTC") not null`, then the value field from the table in section 2
(`Float64`, `Float32`, `Int64`, `Decimal128(18, 6)`, `Decimal128(9, 4)`, `Utf8`; for mixed
`value_numeric: Float64 nullable` and `value_text: Utf8 nullable` with exactly one set per
row). No dictionary encoding in v1, no body compression, schema custom metadata ignored.
Rows sorted by (timeseries_id asc, timestamp asc); equal pairs rejected. No "seconds"
heuristic on timestamps: the type is unambiguous, so any `DateTime64(3)`-representable
value is accepted.

Caps: 100k rows per numeric frame, 10k per text or mixed frame, 10k series per frame,
values at most 64 characters and 256 bytes, 4 MiB raw per frame, 32 frames and 64 MiB raw
per request. Worst-case frames: numeric 2.40 MB, text 2.78 MB, mixed 2.9 MB raw before the
directory, all clearing the broker limit by more than 1 MiB even uncompressed.

Pulsar message properties: `tenantId`, `valueTypeId`, `rows`, `series`, `version`, `codec`,
`compression`. The message payload is the frame exactly as the client sent it.

## 11. Validation on the API, all frames before any publish

1. Acquire the in-flight permit (429 with `Retry-After` when none). Read the raw body; 415
   for any `Content-Encoding`; bounded by the endpoint's compressed cap (413).
2. Walk frames: magic, version, valueTypeId, codec, compression = 1 (anything else is a
   400 with reason `uncompressed-frame`, checked before the payload is touched), caps,
   lengths, the sum of `rawPayloadByteLength` under 64 MiB, last frame ends at the body's
   end, at most 32 frames.
3. Directory: ascending ids, valid UTF-8 external ids, parse ends at the declared length.
4. Payload: decompress into exactly `rawPayloadByteLength` bytes (early end, overrun or
   mismatch rejects), then the minimal IPC codec: the rejection list in section 5, the
   canonical schema for the value type, and buffer views for the id, timestamp and value
   columns of every batch. Frames decompress in parallel.
5. One pass over the id and timestamp views: sortedness across batches, runs `(id, from,
   to)`, run ids equal to the directory ids, per-run last timestamp.
6. Distinct ids across frames through a per-instance series cache (id to dataset id, value
   type, external id, invalidated on timeseries CUD); misses in one query. Unknown ids:
   404.
7. Per distinct dataset once: the dataset write check. Orphans need write-everything, as
   today.
8. Per series: value type equals the frame's, external id equals the directory's. Else 422
   listing the ids.
9. Quotas after validation: datapoints, text datapoints, and bytes on decompressed bytes.
10. Publish each frame in order as one message holding the client's bytes as received,
    still compressed; update the latest-value cache once per run; count the ingest;
    release the permit; respond 204.

Errors are `application/problem+json` with `reason`, `frameIndex`, `timeseriesIds`.

## 12. The read path

Arrow is the retrieval format: the data list endpoint gains an Arrow stream response,
produced by ClickHouse with `FORMAT ArrowStream` and streamed through the API without a
Valkey spool or a cursor, compressed with zstd on the wire. The Java SDK reads it with the
same minimal codec into arrays, or with full arrow-java through an optional module; Rust
with arrow-rs; Python receives it through the PyCapsule interface for polars and pyarrow.
Its design is a separate piece of work.
