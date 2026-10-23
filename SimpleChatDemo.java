package Chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleChatDemo {

    public static void main(String[] args) {
        ChatService chat = new ChatService();
        chat.registerUser(new User("u1", "Alice"));
        chat.registerUser(new User("u2", "Bob"));
        chat.registerUser(new User("u3", "Carol"));

        chat.subscribe("u1", new ConsoleListener("Alice"));
        chat.subscribe("u2", new ConsoleListener("Bob"));
        chat.subscribe("u3", new ConsoleListener("Carol"));

        // ---------- one-to-one ----------
        System.out.println("=== One-to-one chat (Alice and Bob) ===");
        String dm = chat.openDirectChat("u1", "u2");
        chat.sendMessage(dm, "u1", "Hi Bob!");
        chat.sendMessage(dm, "u2", "Hey Alice!");
        chat.sendMessage(dm, "u1", "Lunch today?");
        
        System.out.println("Opening the chat from Bob's side gives the same chat? "
                + dm.equals(chat.openDirectChat("u2", "u1")));

        // ---------- group ----------
        System.out.println("\n=== Group chat (Alice, Bob, Carol) ===");
        String group = chat.createGroup("u1", "Project Team", Arrays.asList("u2", "u3"));
        chat.sendMessage(group, "u1", "Welcome everyone");
        chat.sendMessage(group, "u3", "Thanks Alice!");
        chat.sendMessage(group, "u2", "Hello team");
        

        // ---------- history ----------
        printHistory(chat, "Alice's history in the one-to-one chat", dm, "u1");
        printHistory(chat, "Bob's history in the group", group, "u2");
        System.out.println("\nAlice's conversations: " + chat.getConversationsOf("u1"));
        System.out.println("Carol's conversations: " + chat.getConversationsOf("u3"));

        // ---------- rules ----------
        System.out.println("\n=== Rules ===");
        try {
            chat.getHistory(dm, "u3"); // Carol is not part of Alice and Bob's chat
        } catch (ChatException e) {
            System.out.println("Carol reading the Alice-Bob chat -> " + e.getMessage());
        }
        List<Message> history = chat.getHistory(group, "u1");
        try {
            history.add(history.get(0)); // history cannot be edited from outside
        } catch (UnsupportedOperationException e) {
            System.out.println("Changing a history list -> not allowed (messages are immutable)");
        }
        
    }

    private static void printHistory(ChatService chat, String title, String conversationId, String userId) {
        System.out.println("\n--- " + title + " ---");
        for (Message m : chat.getHistory(conversationId, userId)) {
            System.out.println("#" + m.sequence() + "  " + m.senderId() + ": " + m.content());
        }
    }
}

/* ============================ Entities ============================ */

record User(String id, String name) {}

record Message(String conversationId, long sequence, String senderId, String content, Instant timestamp) {}

class ChatException extends RuntimeException {
    ChatException(String message) {
        super(message);
    }
}

/* ============================ Delivery (Observer pattern) ============================ */

interface MessageListener {
    void onMessage(Message message);
}

/** Knows who is listening for which user. Later this could push over WebSockets or mobile push. */
class MessageDispatcher {
    private final Map<String, MessageListener> listeners =
            new ConcurrentHashMap<>();

    void subscribe(String userId, MessageListener listener) {
        listeners.put(userId, listener);
    }

    void dispatch(Message message, Collection<String> recipientIds) {
        for (String userId : recipientIds) {
            MessageListener userListener = listeners.get(userId);
            if (userListener != null) {
                userListener.onMessage(message);
            }
        }
    }

    // in case of multidevice, we can store List<MessageListener> and the loop over.
}

/* ============================ Conversations ============================ */

abstract class Conversation {
    private final String id;
    private final MessageDispatcher dispatcher;
    private final List<Message> messages = new ArrayList<>(); // append-only
    private long nextSequence = 1;

    Conversation(String id, MessageDispatcher dispatcher) {
        this.id = id;
        this.dispatcher = dispatcher;
    }

    abstract boolean isParticipant(String userId);
    abstract Set<String> getParticipantIds();

    synchronized Message addMessage(String senderId, String content) {
        if (!isParticipant(senderId)) {
            throw new ChatException("User " + senderId + " is not part of conversation " + id);
        }
        Message message = new Message(id, nextSequence, senderId, content, Instant.now());
        nextSequence++;
        messages.add(message);

        Set<String> recipients = getParticipantIds();
        recipients.remove(senderId); // do not send it back to the sender
        dispatcher.dispatch(message, recipients);
        return message;
    }

    synchronized List<Message> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }
}

class OneToOneConversation extends Conversation {
    private final String userA;
    private final String userB;

    OneToOneConversation(String id, MessageDispatcher dispatcher, String userA, String userB) {
        super(id, dispatcher);
        this.userA = userA;
        this.userB = userB;
    }

    @Override
    boolean isParticipant(String userId) {
        return userA.equals(userId) || userB.equals(userId);
    }

    @Override
    Set<String> getParticipantIds() {
        return new HashSet<>(Arrays.asList(userA, userB));
    }
}

class GroupConversation extends Conversation {
    private final String name;
    private final Set<String> members;

    GroupConversation(String id, MessageDispatcher dispatcher, String name, Set<String> members) {
        super(id, dispatcher);
        this.name = name;
        this.members = members;
    }

    @Override
    boolean isParticipant(String userId) {
        return members.contains(userId);
    }

    @Override
    Set<String> getParticipantIds() {
        return new HashSet<>(members);
    }
}

/* ============================ Service (public API) ============================ */

class ChatService {
    private final Map<String, User> users = new ConcurrentHashMap<>();

    // conversationId -> Conversation (one-to-one and group)
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    // sorted "userA|userB" -> conversationId (prevents duplicate one-to-one chats)
    private final Map<String, String> directChatIndex = new ConcurrentHashMap<>();

    // userId -> conversation ids the user belongs to
    private final Map<String, Set<String>> userConversations = new ConcurrentHashMap<>();
    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private final AtomicLong dmCounter = new AtomicLong(1);
    private final AtomicLong groupCounter = new AtomicLong(1);

    void registerUser(User user) {
        users.put(user.id(), user);
        userConversations.put(user.id(), ConcurrentHashMap.newKeySet());
    }

    private void requireUser(String userId) {
        if (!users.containsKey(userId)) {
            throw new ChatException("Unknown user: " + userId);
        }
    }

    void subscribe(String userId, MessageListener listener) {
        requireUser(userId);
        dispatcher.subscribe(userId, listener);
    }

    /** Returns the existing one-to-one chat for these two users, or creates it. */
    synchronized String openDirectChat(String userA, String userB) {
        requireUser(userA);
        requireUser(userB);
        if (userA.equals(userB)) {
            throw new ChatException("Cannot open a chat with yourself");
        }
        String key = userA.compareTo(userB) < 0 ? userA + "|" + userB : userB + "|" + userA;
        String existingId = directChatIndex.get(key);
        if (existingId != null) {
            return existingId;
        }
        String id = "C-" + dmCounter.getAndIncrement();
        conversations.put(id, new OneToOneConversation(id, dispatcher, userA, userB));
        directChatIndex.put(key, id);
        userConversations.get(userA).add(id);
        userConversations.get(userB).add(id);
        return id;
    }

    String createGroup(String creatorId, String name, List<String> otherMemberIds) {
        Set<String> members = new HashSet<>(otherMemberIds);
        members.add(creatorId);
        for (String memberId : members) {
            requireUser(memberId);
        }
        String id = "G-"+groupCounter.getAndIncrement();
        conversations.put(id, new GroupConversation(id, dispatcher, name, members));
        for (String memberId : members) {
            userConversations.get(memberId).add(id);
        }
        return id;
    }

    Message sendMessage(String conversationId, String senderId, String content) {
        requireUser(senderId);
        if (content == null || content.trim().isEmpty()) {
            throw new ChatException("Message cannot be empty");
        }
        return getConversation(conversationId).addMessage(senderId, content);
    }

    /** Complete history of one conversation, only for its participants. */
    List<Message> getHistory(String conversationId, String requesterId) {
        requireUser(requesterId);
        Conversation conversation = getConversation(conversationId);
        if (!conversation.isParticipant(requesterId)) {
            throw new ChatException("User " + requesterId + " is not part of conversation " + conversationId);
        }
        return conversation.getHistory();
    }

    Set<String> getConversationsOf(String userId) {
        requireUser(userId);
        return new TreeSet<>(userConversations.get(userId));
    }

    private Conversation getConversation(String id) {
        Conversation conversation = conversations.get(id);
        if (conversation == null) {
            throw new ChatException("Unknown conversation: " + id);
        }
        return conversation;
    }
}

/* ============================ Demo helper ============================ */

class ConsoleListener implements MessageListener {
    private final String owner;

    ConsoleListener(String owner) {
        this.owner = owner;
    }

    public void onMessage(Message m) {
        System.out.println("   [" + owner + " receives] " + m.conversationId() + " #" + m.sequence()
                + " from " + m.senderId() + ": " + m.content());
    }
}
