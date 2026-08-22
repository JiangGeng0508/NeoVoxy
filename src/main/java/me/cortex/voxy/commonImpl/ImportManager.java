package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.importers.IDataImporter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ImportManager {
    //TODO:
    //Taskbar.INSTANCE.setProgress(0,10000);
    //Taskbar.INSTANCE.setIsProgression();
    //Taskbar.INSTANCE.setProgress(a, Math.max(1, b));
    //Taskbar.INSTANCE.setIsNone();

    private final Map<WorldEngine, ImportTask> activeImporters = new HashMap<>();

    protected class ImportTask {
        protected final IDataImporter importer;
        protected long startTime;
        protected long timer;
        protected long updateEvery = 50;
        private boolean started;

        protected ImportTask(IDataImporter importer) {
            this.importer = importer;
            this.timer = System.currentTimeMillis();
        }

        private void start() {
            if (this.importer.isRunning()) {
                throw new IllegalStateException();
            }
            this.started = true;
            this.startTime = System.currentTimeMillis();
            this.importer.runImport(this::onUpdate, this::onCompleted);
        }

        protected boolean onUpdate(int completed, int outOf) {
            if (System.currentTimeMillis() - this.timer < this.updateEvery)
                return false;
            this.timer = System.currentTimeMillis();

            //TODO: THING

            return true;
        }

        protected void onCompleted(int total) {
            ImportManager.this.jobFinished(this);
        }

        protected void shutdown() {
            this.importer.shutdown();
        }

        private boolean isStale() {
            //A task that was started but whose importer is no longer running either
            // finished without reporting completion or crashed mid-import; either way it
            // must not block new imports for this world forever.
            return this.started && !this.importer.isRunning();
        }

        protected boolean isCompleted() {
            return !this.importer.isRunning();
        }
    }

    protected synchronized ImportTask createImportTask(IDataImporter importer) {
        return new ImportTask(importer);
    }

    public boolean tryRunImport(IDataImporter importer) {
        ImportTask task;
        synchronized (this) {
            var importerTask = this.activeImporters.get(importer.getEngine());
            if (importerTask != null) {
                if (!importerTask.isStale()) {
                    return false;
                }
                //Stale entry (finished/crashed without reporting completion), replace it
                // instead of throwing so it cant block new imports forever
                this.activeImporters.remove(importer.getEngine());
            }
            task = this.createImportTask(importer);
            this.activeImporters.put(importer.getEngine(), task);
        }
        task.start();
        return true;
    }

    public boolean makeAndRunIfNone(WorldEngine engine, Supplier<IDataImporter> factory) {
        try {
            engine.acquireRef();
            synchronized (this) {
                var existing = this.activeImporters.get(engine);
                if (existing != null) {
                    if (!existing.isStale()) {
                        return false;
                    }
                    this.activeImporters.remove(engine);
                }
            }
            return this.tryRunImport(factory.get());
        } finally {
            engine.releaseRef();
        }
    }

    public boolean cancelImport(WorldEngine engine) {
        ImportTask task;
        synchronized (this) {
            task = this.activeImporters.get(engine);
            if (task == null) {
                return false;
            }
        }
        task.shutdown();
        synchronized (this) {
            this.activeImporters.remove(engine);
        }
        return true;
    }

    private synchronized void jobFinished(ImportTask task) {
        //if (!task.isCompleted()) {
        //    throw new IllegalStateException();
        //}

        var remTask = this.activeImporters.remove(task.importer.getEngine());
        if (remTask != null) {
            if (remTask != task) {
                throw new IllegalStateException();
            }
        }
    }
}
