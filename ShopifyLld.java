package ShopifyLld;
import java.util.*;


public class ShopifyLld {

    enum OrderStatus {
        PLACED,
        PAYMENT_FAILED
    }
    
    static class Product {
        private final String id;
        private final String storeId;
        private final String name;
        private final long priceInPaise;
        private int inventory;

        Product(String id, String storeId, String name, long priceInPaise, int inventory){
            if(inventory <0 || priceInPaise <0){
                throw new IllegalArgumentException("Price and inventory can't be negative");
            }
            this.id = id;
            this.name = name;
            this.storeId = storeId;
            this.priceInPaise = priceInPaise;
            this.inventory = inventory;

        }

        void deduct(int quantity){
            if(quantity < 0){
                throw new IllegalArgumentException("Quantity can't be negative");
            }
            if(inventory < quantity){
                throw new IllegalStateException("Quantity is not available");
            }
            inventory -= quantity;
        }

        void restore(int quantity){
            if(quantity <= 0){
                throw new IllegalArgumentException("Quantity can't be zero or negative");
            }
            inventory += quantity;
        }

        String getStoreId(){
            return this.storeId;
        }
        String getId(){
            return this.id;
        }
        String getName(){
            return name;
        }
        long getPrice(){
            return priceInPaise;
        }
        int getInventory(){
            return inventory;
        }
    }

    static class Store{
        private final String id;
        private final String name;
        private final Map<String, Product> products = new HashMap<>();

        Store(String id, String name){
            this.id = id;
            this.name = name;
        }

        String getId(){
            return this.id;
        }
        String getName(){
            return this.name;
        }

        void addProduct(Product product){
            if(!id.equals(product.getStoreId())){
                System.out.println(id);
                 System.out.println(product.getStoreId());
                throw new IllegalArgumentException("product belong to diff store");
            }
            products.put(product.getId(), product);
        }

        Product getProduct(String productId){
            Product product = products.get(productId);
            if(product == null){
                throw new IllegalArgumentException("product is null");
            }
            return product;
        }

        Collection<Product> getProducts(){
            return Collections.unmodifiableCollection(products.values());
        }
    }

    static class Cart{
        private final String id;
        private final String StoreId;
        private final Map<String, Integer> items = new HashMap<>();

        public Cart(String id, String StoreId) {
            this.id = id;
            this.StoreId = StoreId;
        }

        String getId(){
            return id;
        }
        String getStoreId(){
            return StoreId;
        }
        Map<String, Integer> getItems(){
            return Collections.unmodifiableMap(items);
        }

        void add(String productId, int quantity){
            if(quantity < 0){
                throw new IllegalArgumentException("Quantity can't be negative");
            }
            items.merge(productId, quantity, Integer::sum);
        }

        void remove(String productId, int quantity){
            if(quantity < 0){
                throw new IllegalArgumentException("Quantity can't be negative");
            }
            items.put(id, items.get(productId) - quantity);
        }

        void clearCart(){
            items.clear();
        }

    }

    static class OrderItem{
        private final String productId;
        private final String productname;
        private final int quantity;
        private final long unitPriceInPaise;

        public OrderItem(Product product, int quantity) {
            this.productId = product.getId();
            this.productname = product.getName();
            this.quantity = quantity;
            this.unitPriceInPaise = product.getPrice();
        }

        long getLineTotalInPaise(){
            return unitPriceInPaise*quantity;
        }

        @Override 
        public String toString(){
            return productname + "*" + quantity;
        }
    }

    static class Order{
        private final String id;
        private final String storeId;
        private final List<OrderItem> items;
        private final long totaInPaise;
        private final OrderStatus status;

        public Order(String id, String storeId, List<OrderItem> items, OrderStatus status) {
            this.id = id;
            this.storeId =storeId;
            this.items = items;
            this.totaInPaise = items.stream()
                                    .mapToLong(OrderItem::getLineTotalInPaise)
                                    .sum();
            this.status = status;
        }

        String getId(){return id;}
        String getStoreId(){return storeId;}
        long getLineTotalInPaise(){return  totaInPaise;}
        OrderStatus getOrderStatus(){return status;}

        @Override 
        public String toString(){
            return "Order Placed" + id;
        }
    }
    
    interface PaymentService{
        boolean charge(String OrderId, long amountInPaise);
    }

    static class MockPaymentService implements PaymentService{
        @Override 
        public boolean charge(String Orderid, long amountInPaise){
            System.out.println("Charging " + amountInPaise/100 + "." + amountInPaise%100 + "for OrderId:" + Orderid);
            return false;
        }
    }

    static class CartService{
        private final Map<String, Cart> carts = new HashMap<>();
        private final Map<String, Store> stores;

        public CartService(Map<String, Store> stores) {
            this.stores = stores;
        }

        Cart createCart(String cartId, String storeId){
            requireStore(storeId);
            Cart cart = new Cart(cartId, storeId);
            carts.put(cartId, cart);
            return cart;
        }

        void addToCart(String cartId, String productId, int quantity){
            Cart cart = requireCart(cartId);
            Store store = requireStore(cart.getStoreId());
            store.getProduct(productId);
            cart.add(productId, quantity);
        }

        Cart requireCart(String cartId){
            Cart cart = carts.get(cartId);
            if(cart == null){
                throw new IllegalArgumentException("Store doesn't exists");
            }
            return cart;
        }

        Store requireStore(String storeId){
            Store store = stores.get(storeId);
            if(store == null){
                throw new IllegalArgumentException("Store doesn't exists");
            }
            return store;
        }
    }

    static class OrderService{
        private final CartService cartService;
        private final PaymentService paymentService;
        private final Map<String, Order> orders = new HashMap<>();

        public OrderService(CartService cartService, PaymentService paymentService) {
            this.cartService = cartService;
            this.paymentService = paymentService;
        }

        Order placeOrder(String cartId){
            Cart cart = cartService.requireCart(cartId);
            Store store = cartService.requireStore(cart.getStoreId());

            if(cart.getItems().isEmpty()){
                throw new IllegalStateException("Cannot place an empty order");
            }

            List<OrderItem> orderItems = new ArrayList<>();
            for(Map.Entry<String, Integer> entry : cart.getItems().entrySet()){
                Product product = store.getProduct(entry.getKey());
                int quantity = entry.getValue();
                if(quantity <= 0 || product.getInventory() < quantity){
                    throw new IllegalStateException("Insufficeint inventory" + product.getName());
                }
                orderItems.add(new OrderItem(product, quantity));
            }

            String orderId = UUID.randomUUID().toString();
            for(Map.Entry<String,Integer> entry : cart.getItems().entrySet()){
                store.getProduct(entry.getKey()).deduct(entry.getValue());
            }

            long total = orderItems.stream()
                                    .mapToLong(OrderItem::getLineTotalInPaise)
                                    .sum();

            try {
                if(!paymentService.charge(orderId, total)){
                    restoreInventory(store, cart);
                    throw new IllegalStateException("payment failed");
                }else {
                    Order order = new Order(orderId, store.getId(), orderItems, OrderStatus.PLACED);
                    orders.put(orderId, order);
                    cart.clearCart();
                    return order;
                }
            } catch (RuntimeException e) {
                restoreInventory(store, cart);
                // throw e;
            }
            return null;
        }

        private void restoreInventory(Store store, Cart cart){
            for(Map.Entry<String, Integer> entry : cart.getItems().entrySet()){
                store.getProduct(entry.getKey()).restore(entry.getValue());
            }
        }     
    }

    public static void main(String[] args) {
        Store store = new Store("store-1", "Deepak shop");
        store.addProduct(new Product("P-1", "store-1", "Keyboard", 5, 10));
        store.addProduct(new Product("P-2", "store-1", "Mouse", 25, 5));

        Map<String, Store> stores = new HashMap<>();
        stores.put(store.getId(),store);

        CartService cartService = new CartService(stores);
        OrderService orderService = new OrderService(cartService, new MockPaymentService());

        cartService.createCart("cart-1", "store-1");
        cartService.addToCart("cart-1", "P-1", 10);
        cartService.addToCart("cart-1", "P-2", 1);

        Order order = orderService.placeOrder("cart-1");
        System.out.println(order);
        System.out.println("keyboard inventory left:" + store.getProduct("P-1").getInventory());
    }


}
