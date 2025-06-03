package searchengine.servicesImpl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteModel;
import searchengine.model.Status;
import searchengine.repository.LemmaRepos;
import searchengine.repository.PageRepos;
import searchengine.repository.SiteRepos;
import searchengine.services.StatisticsService;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SitesList sitesList;
    private final SiteRepos siteRepos;
    private final PageRepos pageRepos;
    private final LemmaRepos lemmaRepos;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics total = new TotalStatistics();
        List<DetailedStatisticsItem> detailedList = new ArrayList<>();

        List<SiteModel> dbSiteModels = siteRepos.findAll();

        total.setSites(dbSiteModels.size());
        total.setIndexing(dbSiteModels.stream().anyMatch(site -> site.getStatus().equals(Status.INDEXING)));

        for (SiteModel siteModel : dbSiteModels) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(siteModel.getName());
            item.setUrl(siteModel.getUrl());
            item.setStatus(siteModel.getStatus().name());
            item.setStatusTime(siteModel.getStatusTime() != null
                    ? siteModel.getStatusTime().toEpochSecond(java.time.ZoneOffset.UTC)
                    : 0);

            if (siteModel.getLastError() != null && !siteModel.getLastError().isBlank()) {
                item.setError(siteModel.getLastError());
            }

            int pages = pageRepos.countBySiteModel(siteModel);
            int lemmas = lemmaRepos.countBySiteModel(siteModel);

            item.setPages(pages);
            item.setLemmas(lemmas);

            total.setPages(total.getPages() + pages);
            total.setLemmas(total.getLemmas() + lemmas);

            detailedList.add(item);
        }

        StatisticsData statisticsData = new StatisticsData();
        statisticsData.setTotal(total);
        statisticsData.setDetailed(detailedList);

        StatisticsResponse response = new StatisticsResponse();
        response.setResult(true);
        response.setStatistics(statisticsData);

        return response;
    }
}