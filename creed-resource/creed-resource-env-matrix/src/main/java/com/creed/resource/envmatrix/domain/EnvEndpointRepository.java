package com.creed.resource.envmatrix.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EnvEndpointRepository
        extends JpaRepository<EnvEndpoint, Long>, JpaSpecificationExecutor<EnvEndpoint> {

    /**
     * Uniqueness guard for the seven-dimension tuple, checked before insert/update so the API can
     * answer 409 with a readable message instead of surfacing a raw constraint violation.
     */
    Optional<EnvEndpoint> findByAppSystemAndTierAndEnvInstanceAndCountryAndServiceAndInstanceAndScheme(
            String appSystem, String tier, String envInstance,
            String country, String service, String instance, String scheme);

    @Query("select distinct e.appSystem from EnvEndpoint e order by e.appSystem")
    List<String> findDistinctAppSystems();

    @Query("select distinct e.tier from EnvEndpoint e order by e.tier")
    List<String> findDistinctTiers();

    @Query("select distinct e.envInstance from EnvEndpoint e order by e.envInstance")
    List<String> findDistinctEnvInstances();

    @Query("select distinct e.country from EnvEndpoint e order by e.country")
    List<String> findDistinctCountries();

    @Query("select distinct e.service from EnvEndpoint e order by e.service")
    List<String> findDistinctServices();

    @Query("select distinct e.instance from EnvEndpoint e order by e.instance")
    List<String> findDistinctInstances();

    @Query("select distinct e.scheme from EnvEndpoint e order by e.scheme")
    List<String> findDistinctSchemes();
}
