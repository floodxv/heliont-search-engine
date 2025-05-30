package searchengine.services;


import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.lemmatizer.Lemmatizer;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SiteModel;
import searchengine.repository.LemmaRepos;
import searchengine.repository.PageRepos;
import searchengine.repository.SearchIndexRepos;
import searchengine.repository.SiteRepos;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
@Slf4j
public class PageIndexerTask extends RecursiveAction {

    private final SiteModel siteModel;
    private final String path;
    private final PageRepos pageRepos;
    private final LemmaRepos lemmaRepos;
    private final SearchIndexRepos searchIndexRepos;
    private final SiteRepos siteRepos;
    private final Set<String> visitedUrls;
    private final AtomicBoolean stopRequested;

    public PageIndexerTask(SiteModel siteModel,
                           String path,
                           PageRepos pageRepos,
                           LemmaRepos lemmaRepos,
                           SearchIndexRepos searchIndexRepos,
                           SiteRepos siteRepos,
                           Set<String> visitedUrls,
                           AtomicBoolean stopRequested) {
        this.siteModel = siteModel;
        this.path = path;
        this.pageRepos = pageRepos;
        this.lemmaRepos = lemmaRepos;
        this.searchIndexRepos = searchIndexRepos;
        this.siteRepos = siteRepos;
        this.visitedUrls = visitedUrls;
        this.stopRequested = stopRequested;
    }

    @Override
    protected void compute() {
        if (stopRequested.get()) {
            log.info("Индексация остановлена для сайта {}", siteModel.getUrl());
            return;
        }

        String fullUrl = siteModel.getUrl() + normalizePath(path);
        if (!visitedUrls.add(fullUrl)) {
            return;
        }

        try {
            log.info("Индексация страницы: {}", fullUrl);

            Document doc = Jsoup.connect(fullUrl)
                    .userAgent("HeliontSearchBot")
                    .referrer("http://www.google.com")
                    .timeout(10000)
                    .get();

            String normalizedPath = normalizePath(path);

            // Удаление предыдущей версии страницы
            pageRepos.findByPathAndSiteModel(normalizedPath, siteModel).ifPresent(existingPage -> {
                searchIndexRepos.deleteAllByPage(existingPage);
                pageRepos.delete(existingPage);
            });

            Page page = new Page();
            page.setSiteModel(siteModel);
            page.setPath(normalizedPath);
            page.setCode(200);
            page.setContent(doc.outerHtml());
            page.setText(doc.text());
            pageRepos.save(page);

            Map<String, Integer> lemmaCounts = Lemmatizer.getLemmaCounts(doc.text());

            for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
                String lemmaText = entry.getKey();
                int countInPage = entry.getValue();

                Lemma lemma = lemmaRepos.findByLemmaAndSiteModel(lemmaText, siteModel).orElseGet(() -> {
                    Lemma newLemma = new Lemma();
                    newLemma.setLemma(lemmaText);
                    newLemma.setSiteModel(siteModel);
                    newLemma.setFrequency(0);
                    return newLemma;
                });

                lemma.setFrequency(lemma.getFrequency() + 1);
                lemmaRepos.save(lemma);

                Index index = new Index();
                index.setPage(page);
                index.setLemma(lemma);
                index.setRank((float) countInPage);
                searchIndexRepos.save(index);
            }

            List<PageIndexerTask> subTasks = new ArrayList<>();
            Elements links = doc.select("a[href]");

            for (Element link : links) {
                if (stopRequested.get()) break;

                String absHref = link.absUrl("href").split("#")[0].trim();

                if (absHref.isEmpty()
                        || !absHref.startsWith(siteModel.getUrl())
                        || absHref.matches(".*\\.(jpg|jpeg|png|gif|bmp|svg|pdf|zip|tar|gz|mp4|mp3|doc|docx|xls|xlsx)$")) {
                    continue;
                }

                String subPath = normalizePath(absHref.replaceFirst(siteModel.getUrl(), ""));
                String fullSubUrl = siteModel.getUrl() + subPath;

                if (visitedUrls.add(fullSubUrl)) {
                    subTasks.add(new PageIndexerTask(
                            siteModel,
                            subPath,
                            pageRepos,
                            lemmaRepos,
                            searchIndexRepos,
                            siteRepos,
                            visitedUrls,
                            stopRequested
                    ));
                }
            }

            if (visitedUrls.size() > 10000) {
                log.warn("Обнаружено более 10000 страниц на {}. Возможно, зацикливание!", siteModel.getUrl());
            }

            invokeAll(subTasks);

            siteModel.setStatusTime(LocalDateTime.now());
            siteRepos.save(siteModel);

        } catch (IOException e) {
            log.error("Ошибка при индексации {}: {}", fullUrl, e.getMessage());
        }
    }

    private String normalizePath(String rawPath) {
        String cleaned = rawPath.split("#")[0].trim();
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        if (cleaned.endsWith("/") && cleaned.length() > 1) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}