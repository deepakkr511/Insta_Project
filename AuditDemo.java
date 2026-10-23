package Audit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class AuditDemo {

    public static void main(String[] args) {
        // Wire the pieces together. The caller only ever sees the audited service.
        AuditStore auditStore = new InMemoryAuditStore();
        AuditLogger auditLogger = new AuditLogger(auditStore);
        ProductRepository repository = new ProductRepository();
        ProductService productService =
                new AuditedProductService(new ProductServiceImpl(repository), auditLogger);

        User alice = new User("u1", "Alice", "alice@example.com", "10.0.0.1");
        User bob = new User("u2", "Bob", "bob@example.com", "10.0.0.2");

        productService.create(alice, new Product("p1", "Laptop", 1000.0));
        productService.get(bob, "p1");
        productService.update(alice, new Product("p1", "Laptop", 900.0));
        //$$$ productService.changePrice(alice, "p1", 800.0);


        try {
            productService.create(alice, new Product("p1", "Laptop again", 1.0)); // duplicate
        } catch (IllegalStateException e) {
            System.out.println("Alice got an error: " + e.getMessage());
        }
        try {
            productService.update(bob, new Product("p9", "Ghost", 1.0)); // missing
        } catch (NotFoundException e) {
            System.out.println("Bob got an error: " + e.getMessage());
        }

        System.out.println("\n=== Full history of product p1 ===");
        for (AuditLog log : auditLogger.getByProduct("p1")) {
            print(log);
        }

        System.out.println("\n=== Everything bob (u2) did ===");
        for (AuditLog log : auditLogger.getByUser("u2")) {
            print(log);
        }
    }

    private static void print(AuditLog log) {
        System.out.println("#" + log.id() + "->" + log.timestamp());
        System.out.println("who-->" + log.userName() + " from " + log.ipAddress());
        System.out.println("what-->" + log.action() + " " + log.endpoint());
        System.out.println("outcome-->" + log.outcome());
        if (log.before() != null) {
            System.out.println("before-->" + log.before());
        }
        if (log.after() != null) {
            System.out.println("after-->" + log.after());
        }
        if (log.message() != null) {
            System.out.println("message-->" + log.message());
        }
        System.out.println();
    }
}

/* ============================ Enums ============================ */

enum AuditAction {
    CREATE, READ, UPDATE, DELETE
}

enum AuditOutcome {
    SUCCESS, FAILURE
}

/* ============================ Entities ============================ */

record User(String userId, String name, String email, String ipAddress) {}

record Product(String id, String name, double price) {}

/** One audit entry: who did what, when, with what result. Never changed after it is created. */
record AuditLog(long id,
                Instant timestamp,
                String userId,
                String userName,
                String ipAddress,
                AuditAction action,
                String endpoint,
                String productId,
                String before,
                String after,
                AuditOutcome outcome,
                String message) {}

class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
        super(message);
    }
}

/* ============================ REPOSITORY pattern: product storage ============================ */

class ProductRepository {
    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public boolean saveIfAbsent(Product product) {
        return products.putIfAbsent(product.id(), product) == null;
    }
    public Product findById(String id) {
        return products.get(id);
    }
    public Product replace(Product product) {
        return products.replace(product.id(), product);
    }
    public Product remove(String id) {
        return products.remove(id);
    }
}

/* ============================ STRATEGY pattern: audit storage ============================ */

interface AuditStore {
    void append(AuditLog log);

    List<AuditLog> findByUser(String userId);

    List<AuditLog> findByProduct(String productId);
}

class InMemoryAuditStore implements AuditStore {
    private final List<AuditLog> logs = new ArrayList<>();

    @Override
    public synchronized void append(AuditLog log) {
        logs.add(log);
    }

    @Override
    public synchronized List<AuditLog> findByUser(String userId) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog log : logs) {
            if (log.userId().equals(userId)) {
                result.add(log);
            }
        }
        return result;
    }

    @Override
    public synchronized List<AuditLog> findByProduct(String productId) {
        List<AuditLog> result = new ArrayList<>();
        for (AuditLog log : logs) {
            if (log.productId().equals(productId)) {
                result.add(log);
            }
        }
        return result;
    }
}

/** Builds audit entries (id, timestamp) and hands them to the store. Never throws. */
class AuditLogger {
    private final AuditStore store;
    private final AtomicLong nextId = new AtomicLong(1);

    AuditLogger(AuditStore store) {
        this.store = store;
    }

    void log(User user, AuditAction action, String endpoint, String productId,
             String before, String after, AuditOutcome outcome, String message) {
        try {
            AuditLog entry = new AuditLog(nextId.getAndIncrement(), Instant.now(),
                    user.userId(), user.name(), user.ipAddress(),
                    action, endpoint, productId, before, after, outcome, message);
            store.append(entry);
        } catch (RuntimeException e) {
            // Auditing must never break the real request. A real system would alert someone here.
            System.err.println("AUDIT WRITE FAILED for user " + user.userId() + ": " + e);
        }
    }

    List<AuditLog> getByUser(String userId) {
        return store.findByUser(userId);
    }

    List<AuditLog> getByProduct(String productId) {
        return store.findByProduct(productId);
    }
}

/* ============================ The endpoints ============================ */

interface ProductService {
    Product create(User user, Product product);

    Product get(User user, String id);

    Product update(User user, Product product);

    void delete(User user, String id);

    //$$ Product changePrice(User user, String id, double newPrice);
}

/** Pure business logic. It knows nothing about auditing. */
class ProductServiceImpl implements ProductService {
    private final ProductRepository repository;

    ProductServiceImpl(ProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public Product create(User user, Product product) {
        if (!repository.saveIfAbsent(product)) {
            throw new IllegalStateException("Product already exists: " + product.id());
        }
        return product;
    }

    @Override
    public Product get(User user, String id) {
        Product product = repository.findById(id);
        if (product == null) {
            throw new NotFoundException("Product not found: " + id);
        }
        return product;
    }

    @Override
    public Product update(User user, Product product) {
        Product old = repository.replace(product);
        if (old == null) {
            throw new NotFoundException("Product not found: " + product.id());
        }
        return product;
    }

    @Override
    public void delete(User user, String id) {
        Product removed = repository.remove(id);
        if (removed == null) {
            throw new NotFoundException("Product not found: " + id);
        }
    }

    //$$$ @Override 
    // public Product changePrice(User user, String id, double newPrice) {
    // Product existing = get(user, id);                       // throws NotFoundException if missing
    // Product updated = new Product(id, existing.name(), newPrice);
    // repository.replace(updated);
    // return updated;
    // }
}

/* ============================ DECORATOR pattern: adds auditing ============================ */

/**
 * Wraps any ProductService. Every call is audited, success or failure.
 * Because it implements the same interface, a new method on ProductService
 * will not compile here until auditing is added for it.
 */
class AuditedProductService implements ProductService {
    private final ProductService delegate;
    private final AuditLogger auditLogger;

    AuditedProductService(ProductService delegate, AuditLogger auditLogger) {
        this.delegate = delegate;
        this.auditLogger = auditLogger;
    }

    @Override
    public Product create(User user, Product product) {
        String endpoint = "POST /products";
        try {
            Product created = delegate.create(user, product);
            auditLogger.log(user, AuditAction.CREATE, endpoint, product.id(),
                    null, created.toString(), AuditOutcome.SUCCESS, null);
            return created;
        } catch (RuntimeException e) {
            auditLogger.log(user, AuditAction.CREATE, endpoint, product.id(),
                    null, null, AuditOutcome.FAILURE, e.getMessage());
            throw e;
        }
    }

    @Override
    public Product get(User user, String id) {
        String endpoint = "GET /products/" + id;
        try {
            Product product = delegate.get(user, id);
            // Reads record who looked at which product, but not the data itself
            auditLogger.log(user, AuditAction.READ, endpoint, id,
                    null, null, AuditOutcome.SUCCESS, null);
            return product;
        } catch (RuntimeException e) {
            auditLogger.log(user, AuditAction.READ, endpoint, id,
                    null, null, AuditOutcome.FAILURE, e.getMessage());
            throw e;
        }
    }

    @Override
    public Product update(User user, Product product) {
        String endpoint = "PUT /products/" + product.id();
        String before = null;
        try {
            before = delegate.get(user, product.id()).toString(); // old state; throws if missing
            Product updated = delegate.update(user, product);
            auditLogger.log(user, AuditAction.UPDATE, endpoint, product.id(),
                    before, updated.toString(), AuditOutcome.SUCCESS, null);
            return updated;
        } catch (RuntimeException e) {
            auditLogger.log(user, AuditAction.UPDATE, endpoint, product.id(),
                    before, null, AuditOutcome.FAILURE, e.getMessage());
            throw e;
        }
    }
    
    @Override
    public void delete(User user, String id) {
        String endpoint = "DELETE /products/" + id;
        String before = null;
        try {
            before = delegate.get(user, id).toString(); // what is about to be deleted
            delegate.delete(user, id);
            auditLogger.log(user, AuditAction.DELETE, endpoint, id,
                    before, null, AuditOutcome.SUCCESS, null);
        } catch (RuntimeException e) {
            auditLogger.log(user, AuditAction.DELETE, endpoint, id,
                    before, null, AuditOutcome.FAILURE, e.getMessage());
            throw e;
        }
    }

    //$$ @Override 
    // public Product changePrice(User user, String id, double newPrice) {
    //     String endpoint = "PATCH /products/" + id + "/price";
    //     String before = null;
    //     try {
    //         before = delegate.get(user, id).toString();                 // state before
    //         Product updated = delegate.changePrice(user, id, newPrice); // the real work
    //         auditLogger.log(user, AuditAction.UPDATE, endpoint, id,
    //                 before, updated.toString(), AuditOutcome.SUCCESS, null);
    //         return updated;
    //     } catch (RuntimeException e) {
    //         auditLogger.log(user, AuditAction.UPDATE, endpoint, id,
    //                 before, null, AuditOutcome.FAILURE, e.getMessage());
    //         throw e;
    //     }
    // }
}