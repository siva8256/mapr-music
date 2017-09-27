package com.mapr.music.service.impl;

import com.mapr.music.dao.StatisticDao;
import com.mapr.music.model.Statistic;
import com.mapr.music.service.StatisticService;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.ojai.store.cdc.ChangeDataRecord;
import org.ojai.store.cdc.ChangeDataRecordType;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.enterprise.concurrent.ManagedThreadFactory;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.Properties;

@Startup
@Singleton
public class StatisticServiceImpl implements StatisticService {

    private static final String ALBUMS_TABLE_NAME = "/apps/albums";
    private static final String ARTISTS_TABLE_NAME = "/apps/artists";

    private static final String ARTISTS_CHANGE_LOG = "/mapr_music_artists_changelog:artists";
    private static final String ALBUMS_CHANGE_LOG = "/mapr_music_albums_changelog:albums";

    private static final String TEST_USER_NAME = "mapr";
    private static final String TEST_USER_GROUP = "mapr";

    @Resource(lookup = "java:jboss/ee/concurrency/factory/MaprMusicThreadFactory")
    private ManagedThreadFactory threadFactory;

    private final StatisticDao statisticDao;

    @Inject
    public StatisticServiceImpl(@Named("statisticDao") StatisticDao statisticDao) {
        this.statisticDao = statisticDao;
    }

    static class ChangeDataRecordHandler implements Runnable {

        private static long KAFKA_CONSUMER_POLL_TIMEOUT = 500L;

        interface Action {
            void handle(String documentId);
        }

        KafkaConsumer<byte[], ChangeDataRecord> consumer;
        Action onInsert;
        Action onDelete;

        ChangeDataRecordHandler(KafkaConsumer<byte[], ChangeDataRecord> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void run() {
            while (true) {

                ConsumerRecords<byte[], ChangeDataRecord> changeRecords = consumer.poll(KAFKA_CONSUMER_POLL_TIMEOUT);
                for (ConsumerRecord<byte[], ChangeDataRecord> consumerRecord : changeRecords) {

                    // The ChangeDataRecord contains all the changes made to a document
                    ChangeDataRecord changeDataRecord = consumerRecord.value();
                    String documentId = changeDataRecord.getId().getString();

                    // Handle 'RECORD_INSERT'
                    if (changeDataRecord.getType() == ChangeDataRecordType.RECORD_INSERT && this.onInsert != null) {
                        this.onInsert.handle(documentId);
                    }

                    // Handle 'RECORD_DELETE'
                    if (changeDataRecord.getType() == ChangeDataRecordType.RECORD_DELETE && this.onDelete != null) {
                        this.onDelete.handle(documentId);
                    }

                }
            }
        }

        public void setOnInsert(Action onInsert) {
            this.onInsert = onInsert;
        }

        public void setOnDelete(Action onDelete) {
            this.onDelete = onDelete;
        }

    }

    @PostConstruct
    public void init() {

        Properties consumerProperties = new Properties();
        consumerProperties.setProperty("enable.auto.commit", "true");
        consumerProperties.setProperty("auto.offset.reset", "latest");
        consumerProperties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        consumerProperties.setProperty("value.deserializer", "com.mapr.db.cdc.ChangeDataRecordDeserializer");

        loginTestUser(TEST_USER_NAME, TEST_USER_GROUP);

        // Create and adjust consumer which is used to consume MapR-DB CDC events for Albums table.
        KafkaConsumer<byte[], ChangeDataRecord> albumsChangelogConsumer = new KafkaConsumer<>(consumerProperties);
        albumsChangelogConsumer.subscribe(Collections.singletonList(ALBUMS_CHANGE_LOG));
        ChangeDataRecordHandler albumsHandler = new ChangeDataRecordHandler(albumsChangelogConsumer);
        albumsHandler.setOnDelete((id) -> decrementAlbums());
        albumsHandler.setOnInsert((id) -> incrementAlbums());

        // Create and adjust consumer which is used to consume MapR-DB CDC events for Artists table.
        KafkaConsumer<byte[], ChangeDataRecord> artistsChangelogConsumer = new KafkaConsumer<>(consumerProperties);
        artistsChangelogConsumer.subscribe(Collections.singletonList(ARTISTS_CHANGE_LOG));
        ChangeDataRecordHandler artistsHandler = new ChangeDataRecordHandler(artistsChangelogConsumer);
        artistsHandler.setOnDelete((id) -> decrementArtists());
        artistsHandler.setOnInsert((id) -> incrementArtists());

        threadFactory.newThread(albumsHandler).start();
        threadFactory.newThread(artistsHandler).start();
    }

    @Override
    public long getTotalAlbums() {
        Statistic albumsStatistic = getStatisticForTable(ALBUMS_TABLE_NAME);
        return albumsStatistic.getDocumentNumber();
    }

    @Override
    public long getTotalArtists() {
        Statistic artistsStatistic = getStatisticForTable(ARTISTS_TABLE_NAME);
        return artistsStatistic.getDocumentNumber();
    }

    private void incrementAlbums() {
        Statistic albumsStatistic = getStatisticForTable(ALBUMS_TABLE_NAME);
        albumsStatistic.setDocumentNumber(albumsStatistic.getDocumentNumber() + 1);
        statisticDao.update(ALBUMS_TABLE_NAME, albumsStatistic);
    }

    private void decrementAlbums() {
        Statistic albumsStatistic = getStatisticForTable(ALBUMS_TABLE_NAME);
        albumsStatistic.setDocumentNumber(albumsStatistic.getDocumentNumber() - 1);
        statisticDao.update(ALBUMS_TABLE_NAME, albumsStatistic);
    }

    private void incrementArtists() {

        Statistic artistsStatistic = getStatisticForTable(ARTISTS_TABLE_NAME);
        artistsStatistic.setDocumentNumber(artistsStatistic.getDocumentNumber() + 1);
        statisticDao.update(ARTISTS_TABLE_NAME, artistsStatistic);
    }

    private void decrementArtists() {
        Statistic artistsStatistic = getStatisticForTable(ARTISTS_TABLE_NAME);
        artistsStatistic.setDocumentNumber(artistsStatistic.getDocumentNumber() - 1);
        statisticDao.update(ARTISTS_TABLE_NAME, artistsStatistic);
    }

    private Statistic getStatisticForTable(String tableName) {

        Statistic statistic = statisticDao.getById(tableName);
        if (statistic != null) {
            return statistic;
        }

        return statisticDao.create(new Statistic(tableName, 0));
    }

    private static void loginTestUser(String username, String group) {
        UserGroupInformation currentUgi = UserGroupInformation.createUserForTesting(username, new String[]{group});
        UserGroupInformation.setLoginUser(currentUgi);
    }

}
