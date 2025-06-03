package searchengine.servicesImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import searchengine.model.SiteModel;
import searchengine.model.Status;
import searchengine.repository.LemmaRepos;
import searchengine.repository.PageRepos;
import searchengine.repository.SearchIndexRepos;
import searchengine.repository.SiteRepos;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
public class SiteIndexer extends RecursiveAction {

    private static final Logger log = LoggerFactory.getLogger(SiteIndexer.class);

    private final SiteModel siteModel;
    private final SiteRepos siteRepos;
    private final PageRepos pageRepos;
    private final LemmaRepos lemmaRepos;
    private final SearchIndexRepos searchIndexRepos;
    private final AtomicBoolean stopRequested;

    public SiteIndexer(SiteModel siteModel,
                       SiteRepos siteRepos,
                       PageRepos pageRepos,
                       LemmaRepos lemmaRepos,
                       SearchIndexRepos searchIndexRepos,
                       AtomicBoolean stopRequested) {
        this.siteModel = siteModel;
        this.siteRepos = siteRepos;
        this.pageRepos = pageRepos;
        this.lemmaRepos = lemmaRepos;
        this.searchIndexRepos = searchIndexRepos;
        this.stopRequested = stopRequested;
    }

    @Override
    protected void compute() {
        try {
            if (stopRequested.get()) {
                log.info("Индексация сайта {} остановлена до начала", siteModel.getUrl());
                updateStatus(Status.FAILED, "Индексация остановлена пользователем");
                return;
            }

            Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());

            PageIndexerTask rootTask = new PageIndexerTask(
                    siteModel,
                    "/",
                    pageRepos,
                    lemmaRepos,
                    searchIndexRepos,
                    siteRepos,
                    visitedUrls,
                    stopRequested
            );

            ForkJoinTask<?> task = ForkJoinPool.commonPool().submit(rootTask);
            task.join();

            if (stopRequested.get()) {
                log.info("Индексация сайта {} была остановлена", siteModel.getUrl());
                updateStatus(Status.FAILED, "Индексация остановлена пользователем");
            } else {
                log.info("Индексация сайта {} завершена успешно", siteModel.getUrl());
                updateStatus(Status.INDEXED, "");
            }

        } catch (Exception e) {
            log.error("Ошибка при индексации сайта {}: {}", siteModel.getUrl(), e.getMessage());
            updateStatus(Status.FAILED, "Ошибка: " + e.getMessage());
        }
    }

    private void updateStatus(Status status, String error) {
        siteModel.setStatus(status);
        siteModel.setLastError(error == null ? "" : error);
        siteModel.setStatusTime(LocalDateTime.now());
        siteRepos.save(siteModel);
    }
}