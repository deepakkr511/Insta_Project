package NotificationSystem;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SimpleNotificationDemo {

    public static void main(String[] args) throws Exception {
        UserRepository userRepo = new UserRepository();
        PreferenceRepository prefRepo = new PreferenceRepository();
        NotificationLogger logger = new NotificationLogger();

        userRepo.save(new User("u1", "Alice", "alice@example.com", "+1-555-0001"));
        userRepo.save(new User("u2", "Bob", "bob@example.com", "+1-555-0002"));
        userRepo.save(new User("u3", "Carol", "carol@example.com", "")); // no phone

        UserPreferences bobPrefs = new UserPreferences("u2");
        bobPrefs.disable(ChannelType.SMS);
        bobPrefs.disable(ChannelType.PUSH);
        prefRepo.save(bobPrefs);

        List<NotificationChannel> channels = new ArrayList<>();
        channels.add(new EmailChannel());
        channels.add(new SmsChannel(2));   // fails the first 2 times, then works
        channels.add(new PushChannel());   // always fails

        NotificationService service = new NotificationService(userRepo, prefRepo, logger, channels, 3, 100);

        List<ChannelType> allChannels = Arrays.asList(ChannelType.EMAIL, ChannelType.SMS, ChannelType.PUSH);

        Notification n1 = new Notification("n-1", "u1", "Order shipped", "Order #123 is on its way", allChannels);
        Notification n2 = new Notification("n-2", "u2", "Flash sale", "50% off today", allChannels);
        Notification n3 = new Notification("n-3", "u3", "OTP", "Your code is 482910", Arrays.asList(ChannelType.SMS));

        service.send(n1);
        service.send(n2);
        service.send(n3);
        System.out.println("All notifications submitted (send() returned immediately)\n");

        service.shutdown();

        Notification[] all = { n1, n2, n3 };
        for (Notification n : all) {

            System.out.println("--- " + n.id() + " for user " + n.userId() + " ---");
            List<NotificationLog> logs = logger.getLogs(n.id());

            for (ChannelType type : ChannelType.values()) {
                for (NotificationLog log: logs) {
                    if (log.channel() == type) {
                        System.out.println(log.channel() + " " + log.status()
                                + " (attempt " + log.attempt() + "): " + log.message());
                    }
                }
            }
        }
    }
}

enum ChannelType {EMAIL, SMS, PUSH}
enum DeliveryStatus {SENT, RETRYING, FAILED, SKIPPED}

// ====== USER, NOTIFICATION, USER-PREFERENCE =======

record User(String id, String name, String email, String phone) {}
record Notification(String id, String userId, String title, 
    String message, List<ChannelType> channels) {}
record NotificationLog(String notificationId, ChannelType channel, 
    DeliveryStatus status, int attempt, String message) {}

class UserPreferences {
    private final String userId;
    private final Set<ChannelType> disabledChannels = ConcurrentHashMap.newKeySet();

    UserPreferences(String userId) {
        this.userId = userId;
    }

    String getUserId() { return userId; }

    void disable(ChannelType channel) {
        disabledChannels.add(channel);
    }

    boolean isEnabled(ChannelType channel) {
        return !disabledChannels.contains(channel);
    }
}

// =======CLASSES if RECORD is not meant to be used

/* 
class User {
    private final String id, name, email, phone, deviceToken;

    User(String id, String name, String email, String phone, String deviceToken) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.deviceToken = deviceToken;
    }

    String getId() { return id; }
    String getPhone() { return phone; }
}

class Notification {
    private final String id, userId, title, message;
    private final List<ChannelType> channels; // channels the sender wants to use

    Notification(String id, String userId, String title, String message, List<ChannelType> channels) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.message = message;
        this.channels = channels;
    }

    String getId() { return id; }
    String getUserId() { return userId; }
    List<ChannelType> getChannels() { return channels; }
}

class NotificationLog {
    private final String notificationId;
    private final ChannelType channel;
    private final DeliveryStatus status;
    private final int attempt;
    private final String message;

    NotificationLog(String notificationId, ChannelType channel, DeliveryStatus status,
                    int attempt, String message) {
        this.notificationId = notificationId;
        this.channel = channel;
        this.status = status;
        this.attempt = attempt;
        this.message = message;
    }

    String getNotificationId() { return notificationId; }
    ChannelType getChannel() { return channel; }
    DeliveryStatus getStatus() { return status; }
    int getAttempt() { return attempt; }
    String getMessage() { return message; }
}

*/


// ======= Repository Class acts As DATABASE
class UserRepository {
    private final Map<String, User> users = new ConcurrentHashMap<>();

    void save(User user) {
        users.put(user.id(), user);
    }
    User findById(String id) {
        return users.get(id);
    }
}

class PreferenceRepository {
    private final Map<String, UserPreferences> prefs = new ConcurrentHashMap<>();;

    void save(UserPreferences p) {
        prefs.put(p.getUserId(), p);
    }

    UserPreferences findByUserId(String userId) {
        return prefs.get(userId); // null means the user never changed anything
    }
}

class NotificationLogger {
    private final List<NotificationLog> logs = new ArrayList<>();

    synchronized void add(String notificationId, ChannelType channel, DeliveryStatus status,
                          int attempt, String message) {
        logs.add(new NotificationLog(notificationId, channel, status, attempt, message));
    }

    synchronized List<NotificationLog> getLogs(String notificationId) {
        List<NotificationLog> result = new ArrayList<>();
        for (int i = 0; i < logs.size(); i++) {
            if (logs.get(i).notificationId().equals(notificationId)) {
                result.add(logs.get(i));
            }
        }
        return result;
    }
}

// ===== Thrown when send fails and set retry to try again or not

class DeliveryException extends Exception {
    private final boolean retry;

    DeliveryException(String message, boolean retryable) {
        super(message);
        this.retry = retryable;
    }

    boolean isRetryable() { return retry; }
}

// ====== interface inplements by channel
interface NotificationChannel {
    ChannelType getType();
    void send(User user, Notification notification) throws DeliveryException;
}

class EmailChannel implements NotificationChannel {
    @Override 
    public ChannelType getType() {
        return ChannelType.EMAIL;
    }

    @Override 
    public void send(User user, Notification notification) {
        System.out.println("Sent email to: " + user.id());
    }
}

class SmsChannel implements NotificationChannel {
    private int failuresLeft;

    SmsChannel(int failuresLeft) {
        this.failuresLeft = failuresLeft;
    }

    @Override 
    public ChannelType getType() {
        return ChannelType.SMS;
    }

    @Override 
    public synchronized void send(User user, Notification notification) throws DeliveryException {
        if (user.phone() == null || user.phone().isEmpty()) {
            throw new DeliveryException("no phone number on file", false); // permanent, don't retry
        }
        if (failuresLeft > 0) {
            failuresLeft--;
            throw new DeliveryException("SMS gateway timeout", true);     // temporary, retry
        }
    }
}

class PushChannel implements NotificationChannel {
    @Override 
    public ChannelType getType() {
        return ChannelType.PUSH;
    }

    @Override 
    public void send(User user, Notification notification) throws DeliveryException {
        throw new DeliveryException("push service unavailable", true);
    }
}

class NotificationTask implements Runnable {
    private final Notification notification;
    private final User user;
    private final NotificationChannel channel;
    private final NotificationLogger logger;
    private final int maxAttempts;
    private final long initialDelayMillis;

    NotificationTask(Notification notification, User user, NotificationChannel channel,
                     NotificationLogger logger, int maxAttempts, long initialDelayMillis) {
        this.notification = notification;
        this.user = user;
        this.channel = channel;
        this.logger = logger;
        this.maxAttempts = maxAttempts;
        this.initialDelayMillis = initialDelayMillis;
    }

    @Override 
    public void run() {
        String id = notification.id();
        ChannelType type = channel.getType();
        long delay = initialDelayMillis;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                channel.send(user, notification);
                logger.add(id, type, DeliveryStatus.SENT, attempt, "delivered");
                return; // success, we are done
            } catch (DeliveryException e) {
                if (!e.isRetryable() || attempt == maxAttempts) {
                    logger.add(id, type, DeliveryStatus.FAILED, attempt, "not retryable:attempts-exhaust" + e.getMessage());
                    return;
                }

                logger.add(id, type, DeliveryStatus.RETRYING, attempt, e.getMessage() + ", retrying in " + delay + "ms");
                sleep(delay);
                delay = delay * 2; // wait twice as long next time (exponential backoff)
            } catch (RuntimeException e) {
                logger.add(id, type, DeliveryStatus.FAILED, attempt, "unexpected error: " + e);
                return;
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

class NotificationService {
    private final UserRepository userRepo;
    private final PreferenceRepository prefRepo;
    private final NotificationLogger logger;
    private final Map<ChannelType, NotificationChannel> channels = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long initialDelayMillis;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(4);

    NotificationService(UserRepository userRepo, PreferenceRepository prefRepo,
                        NotificationLogger logger, List<NotificationChannel> channelList,
                        int maxAttempts, long initialDelayMillis) {
        this.userRepo = userRepo;
        this.prefRepo = prefRepo;
        this.logger = logger;
        this.maxAttempts = maxAttempts;
        this.initialDelayMillis = initialDelayMillis;
        for (int i = 0; i < channelList.size(); i++) {
            channels.put(channelList.get(i).getType(), channelList.get(i));
        }
    }

    void send(Notification notification) {
        User user = userRepo.findById(notification.userId());
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + notification.userId());
        }
        UserPreferences prefs = prefRepo.findByUserId(user.id());

        List<ChannelType> requested = notification.channels();
        for (int i = 0; i < requested.size(); i++) {
            ChannelType type = requested.get(i);

            if (prefs != null && !prefs.isEnabled(type)) {
                logger.add(notification.id(), type, DeliveryStatus.SKIPPED, 0, "user turned this channel off");
                continue;
            }
            NotificationChannel channel = channels.get(type);
            if (channel == null) {
                logger.add(notification.id(), type, DeliveryStatus.SKIPPED, 0, "no provider for this channel");
                continue;
            }
            threadPool.submit(new NotificationTask(notification, user, channel,
                    logger, maxAttempts, initialDelayMillis));
        }
    }

    /** Stop accepting new work and wait for the running tasks (and their retries) to finish. */
    void shutdown() throws InterruptedException {
        threadPool.shutdown();
        threadPool.awaitTermination(10, TimeUnit.SECONDS);
    }
}

// Strategy -> for Notification channel
// Command -> NotificationTask 
// Repository -> Data access
// Service layer
// Dependenct Injection -> Repository, logger, channels are passed in.



// Without them, main would go straight on to the printing loop while the worker threads were 
// still retrying, and you would see an incomplete log. For example, SMS might show only the 
// first RETRYING entry and no final SENT. Waiting guarantees the logger holds the complete history before it is printed.

// Why it also matters for the program to exit

// The threads in a fixed thread pool are non-daemon threads, so they keep the JVM alive. 
// If you never call shutdown(), the program can finish main and then hang forever with 
// the pool threads idle. Calling it lets those threads end, so the JVM exits normally.



// "Email and SMS go to one address or number, so a user logged in on three devices still gets one email. 
// Push is the case that depends on devices, because each device has its own token.

// I'd store a list of devices per user instead of a single token, and send one delivery task per active 
// device. That way each device has its own retries, backoff and log entry, and a failure on one device 
// doesn't cause duplicate sends to the others. If a provider says a token is invalid, I'd mark that 
// device inactive instead of retrying. I'd treat the notification as delivered if at least one device succeeds."