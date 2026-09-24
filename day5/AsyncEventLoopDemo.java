import java.util.concurrent.CompletableFuture;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class AsyncEventLoopDemo {
    private static String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static void main(String[] args) {
        System.out.println("[" + now() + "] 餐廳開門！大堂經理 (Main Thread) 開始接客");
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 瞬間湧入 10 個使用者的上傳請求
        for (int i = 1; i <= 10; i++) {
            final int taskId = i;
            System.out.println("[" + now() + "] 經理: 幫客人 " + taskId + " 點餐，轉交廚房");
            
            // 模擬非同步處理：把任務交給背景的 Event Loop
            CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // 模擬 S3 網路延遲
                    Thread.sleep(2000); 
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return "客人 " + taskId + " 的炸雞";
            }).thenAccept(result -> {
                String threadName = Thread.currentThread().getName();
                System.out.println("[" + now() + "] 送餐員 (" + threadName + "): 叮咚！" + result + " 好了，送餐 Ｖ");
            });
            
            futures.add(future);
        }

        System.out.println("[" + now() + "] 大堂經理 (Main Thread): 10個客人點餐完畢！經理去旁邊喝咖啡了");
        
        // 【關鍵防呆】把所有號碼牌收集起來，等待所有任務完成才結束程式
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        System.out.println("[" + now() + "] 所有客人皆已送餐完畢，餐廳完美打烊！");
    }
}