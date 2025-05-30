package searchengine.services;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;
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
import java.util.Map;

@Slf4j
@Component
public class SinglePageIndexer {

    private final PageRepos pageRepos;
    private final LemmaRepos lemmaRepos;
    private final SearchIndexRepos searchIndexRepos;
    private final SiteRepos siteRepos;

    public SinglePageIndexer(PageRepos pageRepos,
                             LemmaRepos lemmaRepos,
                             SearchIndexRepos searchIndexRepos,
                             SiteRepos siteRepos) {
        this.pageRepos = pageRepos;
        this.lemmaRepos = lemmaRepos;
        this.searchIndexRepos = searchIndexRepos;
        this.siteRepos = siteRepos;
    }

    public boolean indexPage(String url, SiteModel siteModel) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("HeliontSearchBot")
                    .referrer("http://www.google.com")
                    .timeout(10000)
                    .get();

            String path = normalizePath(url.replace(siteModel.getUrl(), ""));

            // Удалить старую страницу, если существует
            pageRepos.findByPathAndSiteModel(path, siteModel).ifPresent(existing -> {
                searchIndexRepos.deleteAllByPage(existing);
                pageRepos.delete(existing);
            });

            Page page = new Page();
            page.setSiteModel(siteModel);
            page.setPath(path);
            page.setCode(200);
            page.setContent(doc.outerHtml());
            page.setText(doc.text());
            pageRepos.save(page);

            Map<String, Integer> lemmaCounts = Lemmatizer.getLemmaCounts(doc.text());

            for (Map.Entry<String, Integer> entry : lemmaCounts.entrySet()) {
                String lemmaText = entry.getKey();
                int count = entry.getValue();

                Lemma lemma = lemmaRepos.findByLemmaAndSiteModel(lemmaText, siteModel)
                        .orElseGet(() -> {
                            Lemma l = new Lemma();
                            l.setLemma(lemmaText);
                            l.setSiteModel(siteModel);
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

            siteModel.setStatusTime(LocalDateTime.now());
            siteRepos.save(siteModel);
            return true;

        } catch (IOException e) {
            log.error("Ошибка при индексации страницы {}: {}", url, e.getMessage());
            return false;
        }
    }

    private String normalizePath(String rawPath) {
        if (rawPath.isEmpty() || rawPath.equals("/")) return "/";
        String cleaned = rawPath.split("#")[0].trim();
        if (!cleaned.startsWith("/")) cleaned = "/" + cleaned;
        if (cleaned.endsWith("/") && cleaned.length() > 1) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}