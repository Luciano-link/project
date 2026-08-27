package com.luciano.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImagePendingStoreTest {

    @Test
    void putThenTake() {
        String id = ImagePendingStore.put("u1", new byte[]{1, 2, 3}, "a.png");
        assertNotNull(ImagePendingStore.getPending("u1"));
        assertEquals(id, ImagePendingStore.getPending("u1").id());
        assertNotNull(ImagePendingStore.take("u1", id));
        assertNull(ImagePendingStore.getPending("u1"));
    }

    @Test
    void wrongIdNotConsumed() {
        ImagePendingStore.put("u1", new byte[]{1}, "a.png");
        assertNull(ImagePendingStore.take("u1", "wrong-id"));
        assertNotNull(ImagePendingStore.getPending("u1"));
    }

    @Test
    void takeTextOnlyConsumesMatching() {
        ImagePendingStore.putText("u1", "这张图是什么");
        assertEquals("这张图是什么", ImagePendingStore.getPendingText("u1").text());
        assertNotNull(ImagePendingStore.takeText("u1"));
        assertNull(ImagePendingStore.getPendingText("u1"));
    }

    @Test
    void clearRemoves() {
        ImagePendingStore.put("u1", new byte[]{1}, "a.png");
        ImagePendingStore.clear("u1");
        assertNull(ImagePendingStore.getPending("u1"));
    }
}
