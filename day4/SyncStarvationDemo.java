import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class SyncStarvationDemo {
    // 取得當前時間的輔助方法，方便觀察時間軸
    private static String now() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[" + now() + "] 伺服器啟動，連線池容量: 3");
        
        // 1. 模擬 Web 伺服器的 Thread Pool，容量只有 3
        ExecutorService threadPool = Executors.newFixedThreadPool(3);

        // 2. 瞬間湧入 10 個使用者的上傳請求
        for (int i = 1; i <= 10; i++) {
            final int taskId = i;
            threadPool.submit(() -> {
                String threadName = Thread.currentThread().getName();
                System.out.println("[" + now() + "] " + threadName + " 開始處理請求 " + taskId);
                
                try {
                    // 3. 模擬同步呼叫 S3 API：執行緒在這裡「死等」 2 秒
                    Thread.sleep(2000); 
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                
                System.out.println("[" + now() + "] " + threadName + " 請求 " + taskId + " 上傳完成 V");
            });
        }

        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.MINUTES);
        System.out.println("[" + now() + "] 所有請求處理完畢");
    }
}