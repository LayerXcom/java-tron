package org.tron.core.db;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.util.Pair;
import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.exception.BadNumberBlockException;
import org.tron.core.exception.NonCommonBlockException;
import org.tron.core.exception.UnLinkedBlockException;

@Component
public class KhaosDatabase extends TronDatabase {

  public static class KhaosBlock {

    public Sha256Hash getParentHash() {
      return this.blk.getParentHash();
    }

    public KhaosBlock(BlockCapsule blk) {
      this.blk = blk;
      this.id = blk.getBlockId();
      this.num = blk.getNum();
    }

    @Getter
    BlockCapsule blk;
    Reference<KhaosBlock> parent = new WeakReference<>(null);
    BlockId id;
    Boolean invalid;
    long num;

    public KhaosBlock getParent() {
      return parent == null ? null : parent.get();
    }

    public void setParent(KhaosBlock parent) {
      this.parent = new WeakReference<>(parent);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      KhaosBlock that = (KhaosBlock) o;
      return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {

      return Objects.hash(id);
    }
  }

  // KhaosBlockのStore(forkした分まで持つ。) 最新1024 BlockNumまで保持
  public class KhaosStore {

    // (1)BlockID => Block
    private HashMap<BlockId, KhaosBlock> hashKblkMap = new HashMap<>();
    // private HashMap<Sha256Hash, KhaosBlock> parentHashKblkMap = new HashMap<>();
    private int maxCapcity = 1024;

    // (2)挿入順序を保持したHashMap. blockNum => [Block]. 古いものは消されていくので、finalityを得ていく。
    // 最初にきたブロックが優先。
    @Getter
    private LinkedHashMap<Long, ArrayList<KhaosBlock>> numKblkMap =
        new LinkedHashMap<Long, ArrayList<KhaosBlock>>() {

        // capacity維持のために、勝手によばれるメソッド
          @Override
          protected boolean removeEldestEntry(Map.Entry<Long, ArrayList<KhaosBlock>> entry) {
            long minNum = Long.max(0L, head.num - maxCapcity);
            Map<Long, ArrayList<KhaosBlock>> minNumMap = numKblkMap.entrySet().stream()
                .filter(e -> e.getKey() < minNum)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            minNumMap.forEach((k, v) -> {
              numKblkMap.remove(k);
              v.forEach(b -> hashKblkMap.remove(b.id));
            });

            return false;
          }
        };

    public void setMaxCapcity(int maxCapcity) {
      this.maxCapcity = maxCapcity;
    }

    public void insert(KhaosBlock block) {
      hashKblkMap.put(block.id, block);
      // computeIfAbsent: 要素ないときだけ追加
      numKblkMap.computeIfAbsent(block.num, listBlk -> new ArrayList<>()).add(block);
    }

    // block hashをもとにKhaosStoreの2つのHashMapから消す
    public boolean remove(Sha256Hash hash) {
      KhaosBlock block = this.hashKblkMap.get(hash);
      // Sha256Hash parentHash = Sha256Hash.ZERO_HASH;
      if (block != null) {
        long num = block.num;
        // parentHash = block.getParentHash();
        ArrayList<KhaosBlock> listBlk = numKblkMap.get(num);
        if (listBlk != null) {
          listBlk.removeIf(b -> b.id.equals(hash));
        }

        if (CollectionUtils.isEmpty(listBlk)) {
          numKblkMap.remove(num);
        }

        this.hashKblkMap.remove(hash);
        return true;
      }
      return false;
    }

    public List<KhaosBlock> getBlockByNum(Long num) {
      return numKblkMap.get(num);
    }

    public KhaosBlock getByHash(Sha256Hash hash) {
      return hashKblkMap.get(hash);
    }

    public int size() {
      return hashKblkMap.size();
    }

  }

  // HEAD (gitみたいな)
  private KhaosBlock head;

  // 親がいるやつをためるところ
  @Getter
  private KhaosStore miniStore = new KhaosStore();

  // 親がいないときに一時的にためるところ
  @Getter
  private KhaosStore miniUnlinkedStore = new KhaosStore();

  @Autowired
  protected KhaosDatabase(@Value("block_KDB") String dbName) {
    super(dbName);
  }

  @Override
  public void put(byte[] key, Object item) {
  }

  @Override
  public void delete(byte[] key) {
  }

  @Override
  public Object get(byte[] key) {
    return null;
  }

  @Override
  public boolean has(byte[] key) {
    return false;
  }

  void start(BlockCapsule blk) {
    this.head = new KhaosBlock(blk);
    miniStore.insert(this.head);
  }

  void setHead(KhaosBlock blk) {
    this.head = blk;
  }

  // KhaosStoreから実際にBlock消してHEADを変える
  void removeBlk(Sha256Hash hash) {
    if (!miniStore.remove(hash)) {
      miniUnlinkedStore.remove(hash);
    }

    // その後最新HEADにかえる
    head = miniStore.numKblkMap.entrySet().stream()
        .max(Comparator.comparingLong(Map.Entry::getKey))
        .map(Map.Entry::getValue)
        .map(list -> list.get(0))
        .orElseThrow(() -> new RuntimeException("khaosDB head should not be null."));
  }

  /**
   * check if the id is contained in the KhoasDB.
   */
  public Boolean containBlock(Sha256Hash hash) {
    return miniStore.getByHash(hash) != null || miniUnlinkedStore.getByHash(hash) != null;
  }

  public Boolean containBlockInMiniStore(Sha256Hash hash) {
    return miniStore.getByHash(hash) != null;
  }

  /**
   * Get the Block form KhoasDB, if it doesn't exist ,return null.
   */
  public BlockCapsule getBlock(Sha256Hash hash) {
    return Stream.of(miniStore.getByHash(hash), miniUnlinkedStore.getByHash(hash))
        .filter(Objects::nonNull)
        .map(block -> block.blk)
        .findFirst()
        .orElse(null);
  }

  /**
   * Push the block in the KhoasDB.
   */
  public BlockCapsule push(BlockCapsule blk)
      throws UnLinkedBlockException, BadNumberBlockException {
    KhaosBlock block = new KhaosBlock(blk);
    if (head != null && block.getParentHash() != Sha256Hash.ZERO_HASH) {
      KhaosBlock kblock = miniStore.getByHash(block.getParentHash());
      if (kblock != null) {
        // 親がいたとき
        if (blk.getNum() != kblock.num + 1) {
          // 親とblocknumちがうぞ！
          throw new BadNumberBlockException(
              "parent number :" + kblock.num + ",block number :" + blk.getNum());
        }
        block.setParent(kblock);
      } else {
        // 親がいないなら貯めといて例外
        miniUnlinkedStore.insert(block);
        throw new UnLinkedBlockException();
      }
    }

    // 親いる場合はここにたまる
    miniStore.insert(block);

    if (head == null || block.num > head.num) {
      // HEADがうつるよ
      head = block;
    }
    return head.blk;
  }

  public BlockCapsule getHead() {
    return head.blk;
  }

  /**
   * pop the head block then remove it.
   */
  // gitみたいに、上のやつにポインタがうつる
  public boolean pop() {
    KhaosBlock prev = head.getParent();
    if (prev != null) {
      head = prev;
      return true;
    }
    return false;
  }

  public void setMaxSize(int maxSize) {
    miniUnlinkedStore.setMaxCapcity(maxSize);
    miniStore.setMaxCapcity(maxSize);
  }

  /**
   * Find two block's most recent common parent block.
   */
  // 共通祖先までのパスをPair(リスト)でかえす。左は長い方。
  // どちらかの祖先をもっていなかった場合、NonCommonBlockExceptionをthrow.
  public Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> getBranch(Sha256Hash block1, Sha256Hash block2)
      throws NonCommonBlockException {
    LinkedList<KhaosBlock> list1 = new LinkedList<>();
    LinkedList<KhaosBlock> list2 = new LinkedList<>();
    KhaosBlock kblk1 = miniStore.getByHash(block1); // new block
    checkNull(kblk1);
    KhaosBlock kblk2 = miniStore.getByHash(block2); // 信じてたほう
    checkNull(kblk2);

    // 以下、同じ長さになるように、長い方をlistに加えておく
    while (kblk1.num > kblk2.num) {
      list1.add(kblk1);
      kblk1 = kblk1.getParent();
      checkNull(kblk1);
      checkNull(miniStore.getByHash(kblk1.id));
    }

    while (kblk2.num > kblk1.num) {
      list2.add(kblk2);
      kblk2 = kblk2.getParent();
      checkNull(kblk2);
      checkNull(miniStore.getByHash(kblk2.id));
    }

    // 共通祖先にたどりつくまで、それぞれlistに加えていく
    while (!Objects.equals(kblk1, kblk2)) {
      list1.add(kblk1);
      list2.add(kblk2);
      kblk1 = kblk1.getParent();
      checkNull(kblk1);
      checkNull(miniStore.getByHash(kblk1.id));
      kblk2 = kblk2.getParent();
      checkNull(kblk2);
      checkNull(miniStore.getByHash(kblk2.id));
    }

    return new Pair<>(list1, list2);
  }

  private void checkNull(Object o) throws NonCommonBlockException {
    if (o == null) {
      throw new NonCommonBlockException();
    }
  }
  /**
   * Find two block's most recent common parent block.
   */
  @Deprecated
  public Pair<LinkedList<BlockCapsule>, LinkedList<BlockCapsule>> getBranch(
      BlockId block1, BlockId block2) {
    LinkedList<BlockCapsule> list1 = new LinkedList<>();
    LinkedList<BlockCapsule> list2 = new LinkedList<>();
    KhaosBlock kblk1 = miniStore.getByHash(block1);
    KhaosBlock kblk2 = miniStore.getByHash(block2);

    if (kblk1 != null && kblk2 != null) {
      while (!Objects.equals(kblk1, kblk2)) {
        if (kblk1.num > kblk2.num) {
          list1.add(kblk1.blk);
          kblk1 = kblk1.getParent();
        } else if (kblk1.num < kblk2.num) {
          list2.add(kblk2.blk);
          kblk2 = kblk2.getParent();
        } else {
          list1.add(kblk1.blk);
          list2.add(kblk2.blk);
          kblk1 = kblk1.getParent();
          kblk2 = kblk2.getParent();
        }
      }
    }

    return new Pair<>(list1, list2);
  }


  // only for unittest
  public BlockCapsule getParentBlock(Sha256Hash hash) {
    return Stream.of(miniStore.getByHash(hash), miniUnlinkedStore.getByHash(hash))
        .filter(Objects::nonNull)
        .map(KhaosBlock::getParent)
        .map(khaosBlock -> khaosBlock == null ? null : khaosBlock.blk)
        .filter(Objects::nonNull)
        .filter(b -> containBlock(b.getBlockId()))
        .findFirst()
        .orElse(null);
  }

  public boolean hasData() {
    return !this.miniStore.hashKblkMap.isEmpty();
  }
}
