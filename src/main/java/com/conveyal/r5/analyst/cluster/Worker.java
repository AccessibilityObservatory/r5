package com.conveyal.r5.analyst.cluster;

import com.conveyal.analysis.components.WorkerComponents;

public class Worker implements Runnable {

    private final WorkerComponents components;

    public Worker (WorkerComponents components) {
        this.components = components;
    }

    /**
     * The startup process is factored out of LocalWorkerLauncher so it can be reused for cloud-specific contexts
     * where it may be started in a main() method.
     */
    @Override
    public void run () {
        // These two components should probably be refactored to be more orthogonal:
        // regional asynchronous pull task processing, versus synchronous single point and vector tile endpoints.
        components.workerHttpApi.conditionallyEnable();
        components.analysisWorker.startPolling();
    }

}
