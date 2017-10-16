/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.shared.controller.event;

import io.pravega.controller.stream.api.grpc.v1.Controller;
import lombok.Data;

import java.util.concurrent.CompletableFuture;

@Data
public class UpdateStreamEvent implements ControllerEvent {
    private final String scope;
    private final String stream;
    private final int version;
    private final Controller.StreamConfig streamConfig;

    @Override
    public String getKey() {
        return String.format("%s/%s", scope, stream);
    }

    @Override
    public CompletableFuture<Void> process(RequestProcessor processor) {
        return processor.processUpdateStream(this);
    }
}