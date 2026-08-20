package com.owner.lynk10remote;

import java.util.ArrayList;
import java.util.List;

final class CaptureBundle {
    final long capturedAt = System.currentTimeMillis();
    final List<Frame> frames = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    boolean hasFrames() {
        return !frames.isEmpty();
    }

    static final class Frame {
        final String cameraId;
        final String position;
        final byte[] jpeg;
        final int width;
        final int height;

        Frame(String cameraId, String position, byte[] jpeg, int width, int height) {
            this.cameraId = cameraId;
            this.position = position;
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
        }
    }
}
