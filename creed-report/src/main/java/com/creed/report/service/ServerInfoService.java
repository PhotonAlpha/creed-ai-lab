package com.creed.report.service;

import com.creed.report.model.ServerInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ServerInfoService {

    public List<ServerInfo> listServers() {
        return List.of(
                new ServerInfo("creed-auth-01", "10.10.1.11", "creed-author-server", "CN", "auth", "prod", "cn-east-1a", "blue"),
                new ServerInfo("creed-auth-02", "10.10.1.12", "creed-author-server", "CN", "auth", "prod", "cn-east-1b", "green"),
                new ServerInfo("creed-gw-01",   "10.10.2.21", "creed-gateway",       "CN", "gateway", "prod", "cn-east-1a", "blue"),
                new ServerInfo("creed-gw-02",   "10.10.2.22", "creed-gateway",       "SG", "gateway", "prod", "ap-se-1a",   "green"),
                new ServerInfo("creed-cat-01",  "10.10.3.31", "creed-resource-catalog", "CN", "catalog", "staging", "cn-east-1a", "blue"),
                new ServerInfo("creed-ord-01",  "10.10.4.41", "creed-resource-order",   "US", "order",   "prod",    "us-west-2a", "blue"),
                new ServerInfo("creed-ord-02",  "10.10.4.42", "creed-resource-order",   "US", "order",   "prod",    "us-west-2b", "green"),
                new ServerInfo("creed-dev-01",  "10.20.1.11", "creed-author-server",    "CN", "auth",    "dev",     "cn-east-1c", "blue")
        );
    }
}
