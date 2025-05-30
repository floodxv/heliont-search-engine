package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.SiteModel;

@Repository
public interface SearchIndexRepos extends JpaRepository<Index, Long> {

    @Transactional
    @Modifying
    @Query("DELETE FROM Index si WHERE si.page.siteModel = :siteModel")
    void deleteAllBySiteModel(@Param("siteModel") SiteModel site);

    void deleteAllByPage(Page page);

    @Query("SELECT si.rank FROM Index si WHERE si.page = :page AND si.lemma = :lemma")
    Double findRankByPageAndLemma(@Param("page") Page page, @Param("lemma") Lemma lemma);

    boolean existsByPageAndLemma(Page page, Lemma lemma);
}