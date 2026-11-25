package dev.kizuna.meridian.currency;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一个世界一份写前日志；记录落盘即决定交割，可从任意一侧的旧存档恢复。 */
final class CurrencyJournal {
    record Slot(int index, JsonElement stack) {}
    record Entry(CurrencyService.Receipt receipt, List<Slot> payer, List<Slot> payee) {}
    private static final Gson JSON = new Gson();
    private final Path directory;
    private final Map<UUID, Entry> operations = new HashMap<>();
    private final Map<String, List<Entry>> byAccount = new HashMap<>();
    private long latest;
    private UncheckedIOException writeFailure;

    CurrencyJournal(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory.resolve("tills"));
            // 世界目录先落盘，首次开服立即断电也能找到已确认交易。
            syncDirectory(directory.getParent());
            syncDirectory(directory);
            try (var files = Files.list(directory)) {
                var entries = new ArrayList<Entry>();
                for (var file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                    entries.add(JSON.fromJson(Files.readString(file), Entry.class));
                }
                entries.sort(Comparator.comparingLong(entry -> entry.receipt().revision()));
                entries.forEach(this::index);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    Entry find(UUID operation) {
        checkAvailable();
        return operations.get(operation);
    }

    List<Entry> historySince(String account, long watermark) {
        checkAvailable();
        var history = byAccount.getOrDefault(account, List.of());
        int low = 0;
        int high = history.size();
        while (low < high) {
            int middle = low + ((high - low) >>> 1);
            if (history.get(middle).receipt().revision() <= watermark) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return history.subList(low, history.size());
    }

    long next() {
        return Math.incrementExact(latest);
    }

    List<JsonElement> openTill(UUID id, List<JsonElement> opening) {
        checkAvailable();
        var path = directory.resolve("tills").resolve(id + ".json");
        try {
            if (!Files.exists(path)) {
                if (opening.isEmpty()) {
                    throw new IllegalArgumentException("首次打开钱匣需指定真实库存和格数");
                }
                publish(path, JSON.toJson(opening));
            }
            return JsonParser.parseString(Files.readString(path)).getAsJsonArray().asList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    void append(Entry entry) {
        checkAvailable();
        var path = directory.resolve(entry.receipt().operationId() + ".json");
        try {
            publish(path, JSON.toJson(entry));
        } catch (IOException error) {
            // 原子改名和目录刷盘之间报错时，当前进程不能再次分配同一流水号。
            writeFailure = new UncheckedIOException("流水写入结果待重新打开世界核对", error);
            throw writeFailure;
        }
        index(entry);
    }

    private void checkAvailable() {
        if (writeFailure != null) {
            throw writeFailure;
        }
    }

    private void index(Entry entry) {
        var receipt = entry.receipt();
        if (receipt.revision() != next() || operations.putIfAbsent(receipt.operationId(), entry) != null) {
            throw new IllegalStateException("交易流水号缺失或操作号重复");
        }
        latest = receipt.revision();
        var parties = receipt.parties();
        byAccount.computeIfAbsent(parties.payer(), key -> new ArrayList<>()).add(entry);
        byAccount.computeIfAbsent(parties.payee(), key -> new ArrayList<>()).add(entry);
    }

    private static void publish(Path path, String json) throws IOException {
        // 临时文件写齐并刷盘后才原子发布；恢复扫描仅接纳完整的 .json 文件。
        var directory = path.getParent();
        var temporary = Files.createTempFile(directory, "pending-", ".tmp");
        var buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
        try (var out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
            out.force(true /* metaData */);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(directory);
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true /* metaData */);
        }
    }
}
