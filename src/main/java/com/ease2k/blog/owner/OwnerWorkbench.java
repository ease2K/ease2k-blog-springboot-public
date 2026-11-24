package com.ease2k.blog.owner;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;

public interface OwnerWorkbench {

    Entries entries();

    Export export();

    Media media();

    record Entries(List<ObjectNode> entries, List<ObjectNode> trash, List<ObjectNode> drafts, List<ObjectNode> series) {
    }

    record Export(int version, String exportedAt, List<ObjectNode> entries, List<ObjectNode> trash,
                  List<ObjectNode> drafts, List<ObjectNode> series, List<ObjectNode> media) {
    }

    record Media(List<ObjectNode> media) {
    }
}
