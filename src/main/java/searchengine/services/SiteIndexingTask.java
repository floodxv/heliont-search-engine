package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.model.Page;
import searchengine.model.SiteModel;
import searchengine.repository.PageRepos;
import searchengine.repository.SiteRepos;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SiteIndexingTask extends RecursiveTask<List<Page>> {

    private static final int MAX_TASKS = 100;

    private final String pagePath;
    private final SiteModel siteModel;
    private final PageRepos pageRepos;
    private final SiteRepos siteRepos;
    private final Set<String> visitedUrls;
    private final AtomicBoolean stopRequested;

    public SiteIndexingTask(String pagePath, SiteModel siteModel,
                            PageRepos pageRepos, SiteRepos siteRepos,
                            Set<String> visitedUrls, AtomicBoolean stopRequested) {
        this.pagePath = pagePath;
        this.siteModel = siteModel;
        this.pageRepos = pageRepos;
        this.siteRepos = siteRepos;
        this.visitedUrls = visitedUrls;
        this.stopRequested = stopRequested;
    }

    @Override
    protected List<Page> compute() {
        List<Page> result = new ArrayList<>();
        String fullUrl = buildFullUrl(siteModel.getUrl(), pagePath);

        if (stopRequested.get()) {
            log.info("Индексация остановлена: {}", fullUrl);
            return result;
        }

        synchronized (visitedUrls) {
            if (!visitedUrls.add(fullUrl)) {
                return result;
            }
        }

        try {
            if (pageRepos.existsByPathAndSiteModel(pagePath, siteModel)) {
                return result;
            }

            Document doc = Jsoup.connect(fullUrl)
                    .userAgent("HeliontSearchBot")
                    .referrer("http://www.google.com")
                    .timeout(10000)
                    .get();

            Page page = new Page();
            page.setPath(normalizePath(pagePath));
            page.setCode(200);
            page.setContent(doc.outerHtml());
            page.setSiteModel(siteModel);
            pageRepos.save(page);
            result.add(page);

            updateSiteStatusTime(siteModel);

            List<SiteIndexingTask> subTasks = new ArrayList<>();
            Elements links = doc.select("a[href]");

            for (Element link : links) {
                String href = link.absUrl("href").split("#")[0].trim();

                if (!href.startsWith(siteModel.getUrl())) {
                    continue;
                }

                String relPath = normalizePath(href.replaceFirst(siteModel.getUrl(), ""));
                String absHref = siteModel.getUrl() + relPath;

                synchronized (visitedUrls) {
                    if (!visitedUrls.contains(absHref)
                            && !pageRepos.existsByPathAndSiteModel(relPath, siteModel)) {
                        SiteIndexingTask subTask = new SiteIndexingTask(relPath, siteModel, pageRepos, siteRepos, visitedUrls, stopRequested);
                        subTask.fork();
                        subTasks.add(subTask);
                    }
                }
            }

            for (SiteIndexingTask subTask : subTasks) {
                result.addAll(subTask.join());
            }

            if (subTasks.size() > MAX_TASKS) {
                log.warn("Превышено максимальное количество задач: {}", subTasks.size());
            }

        } catch (Exception e) {
            log.error("Ошибка при индексации {}: {}", fullUrl, e.getMessage());
        }

        return result;
    }

    private String buildFullUrl(String siteUrl, String pagePath) {
        try {
            URI base = URI.create(siteUrl);
            URI full = base.resolve(pagePath);
            return full.toString();
        } catch (Exception e) {
            log.warn("Ошибка URL: {} + {}: {}", siteUrl, pagePath, e.getMessage());
            return siteUrl + pagePath;
        }
    }

    private String normalizePath(String rawPath) {
        String path = rawPath.trim().split("#")[0];
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void updateSiteStatusTime(SiteModel siteModel) {
        siteModel.setStatusTime(LocalDateTime.now());
        siteRepos.save(siteModel);
    }
}