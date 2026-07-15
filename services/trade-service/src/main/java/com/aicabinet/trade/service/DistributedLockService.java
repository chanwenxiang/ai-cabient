package com.aicabinet.trade.service;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 闁告帒妫楃粩宄邦嚕韫囨稒鏁氶柡鍫濈Т婵?- 闁糕晞妗ㄧ花?Redisson 閻庡湱鍋熼獮? * 
 * 闁绘婀遍崑锝夋晬? * 1. 闁衡偓椤栨稑鐦梺澶告祰閸ゆ粓宕濋妸褏鏁鹃柡鍫㈠櫐缁辨瑩鎯囩€ｎ喗锛岄柣娆愵殕濠р偓闁告帟顔愮槐? * 2. 闂傚啫寮堕娑橆潰婵犳碍鏁氶柨娑樼墦閺€锝嗘交閸ャ劍鍩傞柤濂変簻婵晠鏌屾繝鍐╂澒闁? * 3. 闁衡偓椤栨稑鐦柛娆樺灦閸ｆ悂宕楅妷鈺傛暁
 * 4. 濡ゅ倹蓱閳ь儸鍡楀幋闁挎稑鐗嗛悢鈧ù?Netty闁? */
@Service
public class DistributedLockService {
    
    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);
    
    private static final String LOCK_PREFIX = "aicabinet:lock:";
    
    @Autowired
    private RedissonClient redissonClient;
    
    /**
     * 閻忓繑绻嗛惁顖炴嚔瀹勬澘绲块柛鎺戞缁斿嘲顕ｈ箛娑欐暁
     * 
     * @param lockKey 闂佸じ鑳跺▓鎴︽煥椤曞棛绀勫☉鎾崇Т閹牓宕滃鍥╃；闁?     * @param leaseTime 闁归晲鐒﹀﹢渚€寮崼鏇燂紵闁挎稑鐗忛～妤呮晬?     * @param waitTime 缂佹稑顦欢鐔煎籍閸洘锛熼柨娑樼墢椤鏁?     * @return 闁哄嫷鍨伴幆渚€鎳㈠畡鏉跨悼闁瑰瓨鍔曟慨?     */
    public boolean tryLock(String lockKey, long leaseTime, long waitTime) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            
            if (acquired) {
                log.debug("闁兼儳鍢茶ぐ鍥礆閸℃顏寸€殿喖绻橀弨锝夊箣閹邦剙顫?key={}", fullKey);
            } else {
                log.warn("闁兼儳鍢茶ぐ鍥礆閸℃顏寸€殿喖绻橀弨锝嗗緞鏉堫偉袝 key={}", fullKey);
            }
            
            return acquired;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("闁兼儳鍢茶ぐ鍥礆閸℃顏寸€殿喖绻橀弨锝囨偖椤惵ゅ幀闁?key={}", fullKey, e);
            return false;
        }
    }
    
    /**
     * 閻忓繑绻嗛惁顖炴嚔瀹勬澘绲块柛鎺戞缁斿嘲顕ｈ箛娑欐暁闁挎稑鐗忛悵娑㈠础鐎圭姷绠查柛銉у剳缁?     */
    public boolean tryLock(String lockKey, long leaseTime) {
        return tryLock(lockKey, leaseTime, 0);
    }
    
    /**
     * 闂佹彃锕ラ弬渚€宕氶崱妤冾伌鐎殿喖绻橀弨?     */
    public void unlock(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("闂佹彃锕ラ弬渚€宕氶崱妤冾伌鐎殿喖绻橀弨锝夊箣閹邦剙顫?key={}", fullKey);
        } else {
            log.warn("鐟滅増鎸告晶鐘电棯鐠恒劉鏌ら柡鍫簼鐎垫棃寮垫径鎰暁 key={}", fullKey);
        }
    }
    
    /**
     * 婵☆偀鍋撻柡灞诲劦閺€锝夊及椤栨碍鍎婇悶姘煎亝鐎垫棃寮?     */
    public boolean isLocked(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        return lock.isLocked();
    }
    
    /**
     * 婵☆偀鍋撻柡灞诲劚缂嶅宕滃鍥ф疇缂佸顑嗗Σ鎼佸触閿旇棄鐦柡鍫濐樀閺€?     */
    public boolean isHeldByCurrentThread(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        return lock.isHeldByCurrentThread();
    }
    
    /**
     * 鐎殿喖鎼崺妤冩喆閿濆鏁氶柨娑樼墕瀹撳嫰姊介埡鍌涙儥濞达絾绮ｇ槐婵囩閸涱垱鏆忓ù婊冩捣椤撴悂鎮堕崱妤佸枀妤犵偞鐓￠。鈺呮晬?     */
    public void forceUnlock(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        
        if (lock.isLocked()) {
            lock.forceUnlock();
            log.warn("鐎殿喖鎼崺妤呮煂婵犲啯鏉归柛鎺戞缁斿嘲顕ｈ箛娑欐暁 key={}", fullKey);
        }
    }

    /**
     * Acquire a lock with lease time
     */
    public RLock acquireLock(String lockKey, long leaseTime) {
        String fullKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullKey);
        try {
            boolean acquired = lock.tryLock(0, leaseTime, TimeUnit.SECONDS);
            if (acquired) {
                return lock;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Release a lock
     */
    public void releaseLock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}