package ai.lambda.examples.databasesessions;

import ai.lambda.agent.core.SessionChange;
import ai.lambda.agent.core.SessionDatabase;
import ai.lambda.agent.core.StoredSession;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sessions in MongoDB. Copy it into your application and adjust names as you like.
 *
 * <p>One document per session holds the version, metadata and messages, so each save is a single
 * atomic update guarded by the version. Media goes in a second collection, one document per file,
 * which keeps the session document far below MongoDB's 16 MB limit.
 */
public final class MongoSessionDatabase implements SessionDatabase {

    private final MongoCollection<Document> sessions;
    private final MongoCollection<Document> media;

    public MongoSessionDatabase(MongoDatabase database) {
        this.sessions = database.getCollection("lambda_sessions");
        this.media = database.getCollection("lambda_session_media");
        media.createIndex(Indexes.ascending("sessionId"));
    }

    @Override
    public Optional<StoredSession> load(String sessionId) {
        Document session = sessions.find(Filters.eq("_id", sessionId)).first();
        if (session == null) return Optional.empty();
        Map<String, byte[]> files = new HashMap<>();
        for (Document file : media.find(Filters.eq("sessionId", sessionId))) {
            files.put(file.getString("name"), file.get("data", Binary.class).getData());
        }
        return Optional.of(new StoredSession(session.getLong("version"), session.getList("messages", String.class),
                session.getString("metadata"), files));
    }

    @Override
    public void write(SessionChange change) {
        String id = change.sessionId();
        // Media first: if the update below fails, at worst an unused file is left, never a
        // message whose media is missing.
        for (Map.Entry<String, byte[]> file : change.addedMedia().entrySet()) {
            media.replaceOne(Filters.eq("_id", id + "/" + file.getKey()),
                    new Document("sessionId", id).append("name", file.getKey()).append("data", new Binary(file.getValue())),
                    new ReplaceOptions().upsert(true));
        }
        if (change.isNew()) {
            try {
                sessions.insertOne(new Document("_id", id)
                        .append("version", change.newVersion())
                        .append("metadata", change.metadataJson())
                        .append("messages", change.newMessages()));
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) throw change.conflict();
                throw e;
            }
        } else {
            // Keep the first keptMessages, then append: one update, applied only at the expected version.
            Object messages = change.keptMessages() == 0
                    ? new Document("$literal", change.newMessages())
                    : new Document("$concatArrays", List.of(
                            new Document("$slice", List.of("$messages", change.keptMessages())),
                            new Document("$literal", change.newMessages())));
            long matched = sessions.updateOne(
                    Filters.and(Filters.eq("_id", id), Filters.eq("version", change.expectedVersion())),
                    List.of(new Document("$set", new Document("version", change.newVersion())
                            .append("metadata", change.metadataJson())
                            .append("messages", messages)))).getMatchedCount();
            if (matched == 0) throw change.conflict();
        }
        if (!change.removedMedia().isEmpty()) {
            media.deleteMany(Filters.in("_id", change.removedMedia().stream().map(name -> id + "/" + name).toList()));
        }
    }
}
