
--
-- Table structure for table `account_sequence`
--

DROP TABLE IF EXISTS `account_sequence`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `account_sequence` (
  `next_val` bigint(20) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `account_sequence`
--

LOCK TABLES `account_sequence` WRITE;
/*!40000 ALTER TABLE `account_sequence` DISABLE KEYS */;
INSERT INTO `account_sequence` VALUES (100000),(100000);
/*!40000 ALTER TABLE `account_sequence` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `orbital_properties`
--

DROP TABLE IF EXISTS `orbital_properties`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `orbital_properties` (
  `propertyName` varchar(191) COLLATE utf8mb4_unicode_ci NOT NULL,
  `propertyValue` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`propertyName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `proxy_access_keys`
--

DROP TABLE IF EXISTS `proxy_access_keys`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `proxy_access_keys` (
  `kid` bigint(20) NOT NULL,
  `accessToken` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `accessTokenExpiry` bigint(20) NOT NULL,
  `characterName` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expiry` bigint(20) NOT NULL,
  `randomSeed` bigint(20) NOT NULL,
  `refreshToken` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `scopes` longtext CHARACTER SET utf8mb4,
  `uid` bigint(20) DEFAULT NULL,
  `serverType` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`kid`),
  KEY `userIndex` (`uid`),
  CONSTRAINT `FKjoer9soplvf4uda7qcnbgjal8` FOREIGN KEY (`uid`) REFERENCES `proxy_users` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `proxy_auth_source`
--

DROP TABLE IF EXISTS `proxy_auth_source`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `proxy_auth_source` (
  `sid` bigint(20) NOT NULL,
  `details` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `last` bigint(20) NOT NULL,
  `screenName` varchar(191) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source` varchar(191) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `uid` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`sid`),
  KEY `accountIndex` (`uid`),
  KEY `sourceAndScreenIndex` (`source`,`screenName`),
  CONSTRAINT `FK18e6pjrup4ah6i14mv2rude8o` FOREIGN KEY (`uid`) REFERENCES `proxy_users` (`uid`),
  CONSTRAINT `FK7ddu85ufo1xcyw12gyn98qv15` FOREIGN KEY (`uid`) REFERENCES `proxy_users` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `proxy_users`
--

DROP TABLE IF EXISTS `proxy_users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `proxy_users` (
  `uid` bigint(20) NOT NULL,
  `active` bit(1) NOT NULL,
  `admin` bit(1) NOT NULL,
  `created` bigint(20) NOT NULL,
  `last` bigint(20) NOT NULL,
  PRIMARY KEY (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
