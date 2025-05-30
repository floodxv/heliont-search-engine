package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;
import searchengine.model.SiteModel;

import java.util.List;
import java.util.Optional;

@Repository
public interface LemmaRepos extends JpaRepository<Lemma, Long> {

    @Transactional
    @Modifying
    @Query("DELETE FROM Lemma l WHERE l.siteModel = :siteModel")
    void deleteAllBySiteModel(@Param("siteModel") SiteModel site);

    Optional<Lemma> findByLemmaAndSiteModel(String lemma, SiteModel site);

    int countBySiteModel(SiteModel site);

    @Query("SELECT COUNT(DISTINCT i.page) FROM Index i WHERE i.lemma.lemma = :lemma AND i.page.siteModel IN :sites")
    long countPagesByLemma(@Param("lemma") String lemma, @Param("sites") List<SiteModel> sites);
}