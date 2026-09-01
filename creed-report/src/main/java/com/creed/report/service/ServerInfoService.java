package com.creed.report.service;

import com.creed.report.i18n.ReportCountry;
import com.creed.report.model.ServerInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ServerInfoService {

    private static final List<ServerInfo> SERVERS = List.of(
            new ServerInfo("creed-auth-01", "10.10.1.11", "creed-author-server", "CN", "auth", "prod", "cn-east-1a", "blue"),
            new ServerInfo("creed-auth-02", "10.10.1.12", "creed-author-server", "CN", "auth", "prod", "cn-east-1b", "green"),
            new ServerInfo("creed-gw-01",   "10.10.2.21", "creed-gateway",       "CN", "gateway", "prod", "cn-east-1a", "blue"),
            new ServerInfo("creed-gw-02",   "10.10.2.22", "creed-gateway",       "SG", "gateway", "prod", "ap-se-1a",   "green"),
            new ServerInfo("creed-cat-01",  "10.10.3.31", "creed-resource-catalog", "CN", "catalog", "staging", "cn-east-1a", "blue"),
            new ServerInfo("creed-ord-01",  "10.10.4.41", "creed-resource-order",   "US", "order",   "prod",    "us-west-2a", "blue"),
            new ServerInfo("creed-ord-02",  "10.10.4.42", "creed-resource-order",   "US", "order",   "prod",    "us-west-2b", "green"),
            new ServerInfo("creed-dev-01",  "10.20.1.11", "creed-author-server",    "CN", "auth",    "dev",     "cn-east-1c", "blue"),
            // Country editions of the report (?country=th|my|vn) scope to these.
            new ServerInfo("creed-th-gw-01",  "10.30.1.11", "creed-gateway",          "TH", "gateway", "prod",    "ap-bkk-1a", "blue"),
            new ServerInfo("creed-th-pay-01", "10.30.2.21", "creed-resource-payment", "TH", "payment", "prod",    "ap-bkk-1a", "blue"),
            new ServerInfo("creed-th-pay-02", "10.30.2.22", "creed-resource-payment", "TH", "payment", "staging", "ap-bkk-1b", "green"),
            new ServerInfo("creed-my-gw-01",  "10.40.1.11", "creed-gateway",          "MY", "gateway", "prod",    "ap-kul-1a", "blue"),
            new ServerInfo("creed-my-ord-01", "10.40.4.41", "creed-resource-order",   "MY", "order",   "prod",    "ap-kul-1a", "green"),
            new ServerInfo("creed-vn-gw-01",  "10.50.1.11", "creed-gateway",          "VN", "gateway", "prod",    "ap-sgn-1a", "blue"),
            new ServerInfo("creed-vn-cat-01", "10.50.3.31", "creed-resource-catalog", "VN", "catalog", "staging", "ap-sgn-1a", "green"),
            new ServerInfo("creed-vn-dev-01", "10.50.9.91", "creed-resource-catalog", "VN", "catalog", "dev",     "ap-sgn-1b", "blue")
    );

    public List<ServerInfo> listServers() {
        return SERVERS;
    }

    /**
     * The servers a country's edition of the report covers.
     *
     * <p>{@link ReportCountry#GLOBAL} has no region and therefore no filter — it is the whole
     * estate, which is what makes it the sensible default.
     */
    public List<ServerInfo> listServers(ReportCountry country) {
        if (country == null || country.region().isEmpty()) {
            return listServers();
        }
        return SERVERS.stream()
                .filter(server -> country.region().equalsIgnoreCase(server.country()))
                .toList();
    }
}
