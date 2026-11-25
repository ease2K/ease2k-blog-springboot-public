package com.ease2k.blog.owner;

import org.springframework.stereotype.Service;

import java.time.Instant;

/** 在同一事务里读取工作台视图，避免一次响应混入不同版本的数据。 */
@Service
public class OwnerWorkbenchService implements OwnerWorkbench {

    private final OwnerDatabase database;
    private final OwnerEntries entries;
    private final OwnerMedia media;

    public OwnerWorkbenchService(OwnerDatabase database, OwnerEntries entries, OwnerMedia media) {
        this.database = database;
        this.entries = entries;
        this.media = media;
    }

    @Override
    public Entries entries() {
        return database.read(this::entryView);
    }

    @Override
    public Export export() {
        return database.read(() -> {
            String exportedAt = Instant.now().toString();
            Entries view = entryView();
            return new Export(2, exportedAt, view.entries(), view.trash(), view.drafts(), view.series(), media.list());
        });
    }

    @Override
    public Media media() {
        return database.read(() -> new Media(media.list()));
    }

    private Entries entryView() {
        return new Entries(entries.all(), entries.trash(), entries.drafts(), entries.series());
    }
}
