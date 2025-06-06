package searchengine.servicesImpl;


import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.lemmatizer.Lemmatizer;
import searchengine.model.*;
import searchengine.repository.LemmaRepos;
import searchengine.repository.PageRepos;
import searchengine.repository.SearchIndexRepos;
import searchengine.repository.SiteRepos;
import searchengine.services.IndexingService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
@Service
public class IndexingServiceImpl implements IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final SiteRepos siteRepos;
    private final PageRepos pageRepos;
    private final LemmaRepos lemmaRepos;
    private final SearchIndexRepos searchIndexRepos;
    private final SitesList sitesList;

    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public IndexingServiceImpl(SiteRepos siteRepos,
                               PageRepos pageRepos,
                               LemmaRepos lemmaRepos,
                               SearchIndexRepos searchIndexRepos,
                               SitesList sitesList) {
        this.siteRepos = siteRepos;
        this.pageRepos = pageRepos;
        this.lemmaRepos = lemmaRepos;
        this.searchIndexRepos = searchIndexRepos;
        this.sitesList = sitesList;
    }

    @Override
    public synchronized boolean startIndexing() {
        if (indexingInProgress.get()) {
            log.info("Индексация уже запущена");
            return false;
        }

        indexingInProgress.set(true);
        stopRequested.set(false);

        log.info("Индексация началась");
        ForkJoinPool forkJoinPool = new ForkJoinPool();
        forkJoinPool.execute(() -> {
            try {
                List<ForkJoinTask<?>> tasks = new ArrayList<>();

                for (searchengine.config.Site configSite : sitesList.getSites()) {
                    SiteModel existingSite = siteRepos.findByUrl(configSite.getUrl());

                    if (existingSite != null) {
                        searchIndexRepos.deleteAllBySiteModel(existingSite);
                        lemmaRepos.deleteAllBySiteModel(existingSite);
                        pageRepos.deleteBySiteModel(existingSite);
                        siteRepos.delete(existingSite);
                    }

                    SiteModel siteModel = new SiteModel();
                    siteModel.setName(configSite.getName());
                    siteModel.setUrl(configSite.getUrl());
                    siteModel.setStatus(Status.INDEXING);
                    siteModel.setStatusTime(LocalDateTime.now());
                    siteModel.setLastError("");
                    siteRepos.save(siteModel);
                    SiteIndexer siteIndexerTask = new SiteIndexer(
                            siteModel,
                            siteRepos,
                            pageRepos,
                            lemmaRepos,
                            searchIndexRepos,
                            stopRequested
                    );

                    ForkJoinTask<?> task = forkJoinPool.submit(siteIndexerTask);
                    tasks.add(task);

                    log.info("Индексация сайта {} запущена", siteModel.getUrl());
                }
                for (ForkJoinTask<?> task : tasks) {
                    task.join();
                }
                indexingInProgress.set(false);
                log.info("Индексация всех сайтов завершена");

            } catch (Exception e) {
                log.error("Ошибка при запуске индексации: {}", e.getMessage());
            }finally {
                indexingInProgress.set(false);
            }
        });
        return true;
    }

    @Override
    public boolean stopIndexing() {
        stopRequested.set(true);
        if (!indexingInProgress.get()) {
            log.info("Индексация не запущена");
            return false;
        }
        indexingInProgress.set(false);

        List<SiteModel> indexingSites = siteRepos.findAllByStatus(Status.INDEXING);
        for (SiteModel siteModel : indexingSites) {
            siteModel.setStatus(Status.FAILED);
            siteModel.setStatusTime(LocalDateTime.now());
            siteModel.setLastError("Индексация остановлена пользователем");
            siteRepos.save(siteModel);
        }

        log.info("Индексация остановлена пользователем");
        return true;
    }

    @Override
    public boolean isIndexing() {
        return indexingInProgress.get();
    }

    @Override
    @Transactional
    public boolean indexPage(String url) {
        if (url == null || url.isBlank()) {
            log.warn("Пустой URL для индексации страницы");
            return false;
        }

        Optional<Site> configSiteOpt = sitesList.getSites().stream()
                .filter(site -> url.startsWith(site.getUrl()))
                .findFirst();

        if (configSiteOpt.isEmpty()) {
            log.warn("URL '{}' не входит в список разрешённых сайтов", url);
            return false;
        }

        String baseUrl = configSiteOpt.get().getUrl();
        SiteModel site = siteRepos.findByUrl(baseUrl);

        if (site == null) {
            log.warn("Сайт для URL '{}' не найден в базе", url);
            return false;
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("HeliontSearchBot")
                    .referrer("http://www.google.com")
                    .timeout(10000)
                    .get();

            String rawPath = url.replace(site.getUrl(), "").split("#")[0];
            String path = rawPath.isEmpty() ? "/" : (rawPath.startsWith("/") ? rawPath : "/" + rawPath);

            pageRepos.findByPathAndSiteModel(path, site).ifPresent(oldPage -> {
                searchIndexRepos.deleteAllByPage(oldPage);
                pageRepos.delete(oldPage);
            });

            Page page = new Page();
            page.setSiteModel(site);
            page.setPath(path);
            page.setCode(200);
            page.setContent(doc.outerHtml());
            page.setText(doc.text());
            pageRepos.save(page);

            Map<String, Integer> lemmas = Lemmatizer.getLemmaCounts(doc.text());

            for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
                String lemmaText = entry.getKey();
                int count = entry.getValue();

                Lemma lemma = lemmaRepos.findByLemmaAndSiteModel(lemmaText, site)
                        .orElseGet(() -> {
                            Lemma l = new Lemma();
                            l.setLemma(lemmaText);
                            l.setSiteModel(site);
                            l.setFrequency(0);
                            return l;
                        });

                lemma.setFrequency(lemma.getFrequency() + 1);
                lemmaRepos.save(lemma);

                Index index = new Index();
                index.setPage(page);
                index.setLemma(lemma);
                index.setRank((float) count);
                searchIndexRepos.save(index);
            }

            site.setStatusTime(LocalDateTime.now());
            siteRepos.save(site);

            log.info("Страница успешно проиндексирована: {}", url);
            return true;

        } catch (IOException e) {
            log.error("Ошибка при индексации страницы {}: {}", url, e.getMessage(), e);
            return true;
        }
    }
}