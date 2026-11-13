package io.github.rainyinsaigon.fraudlinker.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The API scenarios on the in-memory H2 database. Runs everywhere, no Docker needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class H2ApiTest extends ApiScenarios {
}
