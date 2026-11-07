package com.creed.parent.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;


@HttpExchange("https://fcm.googleapis.com/v1/projects")
public interface FirebaseClient {
    String MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    String[] SCOPES = {MESSAGING_SCOPE};

    @PostExchange("/{projectId}/messages:send")
    ResponseEntity<String> sendFirebasePush(@PathVariable("projectId") String projectId,
                                    @RequestBody String push,
                                    @RequestHeader HttpHeaders httpHeaders);
}
