// Firestore 安全規則測試（跑在 Firebase emulator 上，不會碰到任何線上專案）
//
// 執行方式（在 firestore-tests/ 內）：
//   npm install
//   npm test
//
// 測試重點：
//   issue #34
//   - supply_items：共用型錄，沒有擁有者，只能改非負計數欄位
//   - supply_requests / supply_pledges：只有紀錄擁有者能讀、改、刪
//   最關鍵的一條是「使用者能認領別人建立的物資」——那是加擁有者檢查最容易弄壞的地方。
//
//   issue #33
//   - health_reports：含 Detail Tier 全部欄位，只有 Reporter 本人與救援者可讀
//   - rescuers：救援者身分的唯一依據，客戶端一律不可寫（不可自我提權）
//   - users：含電話、緊急聯絡人、病史，只能讀自己的
//   最關鍵的一條是「在 users 自填 role 不會變成救援者」——角色機制最容易寫錯的地方。

import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  increment,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';

const ALICE = 'uid-alice';
const BOB = 'uid-bob';
const RESCUER = 'uid-rescuer';

let testEnv;

const seedItem = {
  name: '礦泉水',
  unit: '瓶',
  category: '食品飲水',
  inventoryQty: 500,
  totalRequestedQty: 0,
  totalPledgedQty: 0,
};

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-cares',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
});

after(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  // 以「繞過規則」的方式鋪底，模擬型錄已經被種子寫入、且 alice 已有一筆認領
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'supply_items/water'), seedItem);
    await setDoc(doc(db, 'pledges/pledge-alice'), {
      userId: ALICE,
      userName: 'Alice',
      itemId: 'water',
      itemName: '礦泉水',
      unit: '瓶',
      quantity: 10,
      status: 'pledged',
      pledgedAt: new Date(),
    });
    // 救援者身分只能由後台（Firebase console / Admin SDK）寫入
    await setDoc(doc(db, `rescuers/${RESCUER}`), { grantedAt: new Date() });
    await setDoc(doc(db, 'health_reports/report-alice'), {
      id: 'report-alice',
      reporterId: ALICE,
      name: 'Alice',
      phone: '0912345678',
      bloodType: 'O',
      status: '重傷',
      description: '左腿骨折，無法行走',
      lat: 25.0339,
      lng: 121.5645,
      reportTime: new Date().toISOString(),
    });
    await setDoc(doc(db, `users/${ALICE}`), {
      id: ALICE,
      name: 'Alice',
      phone: '0912345678',
      emergencyContactPhone: '0987654321',
      medicalInfo: '盤尼西林過敏',
    });
  });
});

const dbFor = (uid) => testEnv.authenticatedContext(uid).firestore();
const anonDb = () => testEnv.unauthenticatedContext().firestore();

describe('supply_items', () => {
  it('登入用戶可讀', async () => {
    await assertSucceeds(getDoc(doc(dbFor(BOB), 'supply_items/water')));
  });

  it('未登入不可讀', async () => {
    await assertFails(getDoc(doc(anonDb(), 'supply_items/water')));
  });

  // ★ issue #34：共用型錄不可做擁有者比對，否則無法登記他人建立的物資。
  it('★ 使用者可以在共用物資上累加需求與捐贈計數', async () => {
    await assertSucceeds(
      updateDoc(doc(dbFor(BOB), 'supply_items/water'), {
        totalRequestedQty: increment(5),
        totalPledgedQty: increment(3),
      }),
    );
  });

  it('未登入不可更新計數', async () => {
    await assertFails(
      updateDoc(doc(anonDb(), 'supply_items/water'), {
        totalPledgedQty: increment(5),
      }),
    );
  });

  it('不可竄改 name', async () => {
    await assertFails(updateDoc(doc(dbFor(BOB), 'supply_items/water'), { name: '假資料' }));
  });

  it('不可在更新計數時夾帶型錄欄位', async () => {
    await assertFails(
      updateDoc(doc(dbFor(BOB), 'supply_items/water'), {
        totalPledgedQty: increment(1),
        category: '衣物',
      }),
    );
  });

  it('計數不可設為負數或非整數', async () => {
    await assertFails(
      updateDoc(doc(dbFor(BOB), 'supply_items/water'), { totalPledgedQty: -1 }),
    );
    await assertFails(
      updateDoc(doc(dbFor(BOB), 'supply_items/water'), { totalRequestedQty: 'many' }),
    );
  });

  it('任何人都不可刪除', async () => {
    await assertFails(deleteDoc(doc(dbFor(BOB), 'supply_items/water')));
    await assertFails(deleteDoc(doc(dbFor(ALICE), 'supply_items/water')));
  });

  it('目前 Dart 模型的種子欄位可以建立', async () => {
    await assertSucceeds(setDoc(doc(dbFor(ALICE), 'supply_items/blanket'), {
      ...seedItem,
      name: '毛毯',
      unit: '條',
      category: '生活用品',
      inventoryQty: 150,
    }));
  });

  it('建立時夾帶多餘欄位會被擋下', async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), 'supply_items/evil'), {
      ...seedItem,
      injected: 'x',
    }));
  });

  it('建立時不得預填需求或捐贈總量', async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), 'supply_items/evil'), {
      ...seedItem,
      totalRequestedQty: 9999,
    }));
    await assertFails(setDoc(doc(dbFor(ALICE), 'supply_items/evil'), {
      ...seedItem,
      totalPledgedQty: 9999,
    }));
  });

  it('建立時欄位型別錯誤會被擋下', async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), 'supply_items/evil'), {
      ...seedItem,
      inventoryQty: '500',
    }));
  });

  it('建立時缺欄位會被擋下', async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), 'supply_items/evil'), { name: '只有名字' }));
  });
});

describe('supply_requests', () => {
  const newRequest = (uid) => ({
    userId: uid,
    userName: 'Someone',
    itemId: 'water',
    itemName: '礦泉水',
    unit: '瓶',
    quantity: 3,
    status: 'pending',
    createdAt: serverTimestamp(),
  });

  it('可以建立自己的物資申請', async () => {
    await assertSucceeds(
      setDoc(doc(dbFor(BOB), 'supply_requests/request-bob'), newRequest(BOB)),
    );
  });

  it('不可冒用他人 userId 建立申請', async () => {
    await assertFails(
      setDoc(doc(dbFor(BOB), 'supply_requests/request-fake'), newRequest(ALICE)),
    );
  });

  it('數量必須為正整數，且狀態必須從 pending 開始', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), 'supply_requests/request-zero'), {
      ...newRequest(BOB),
      quantity: 0,
    }));
    await assertFails(setDoc(doc(dbFor(BOB), 'supply_requests/request-status'), {
      ...newRequest(BOB),
      status: 'fulfilled',
    }));
  });

  it('建立時不可夾帶未定義欄位', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), 'supply_requests/request-extra'), {
      ...newRequest(BOB),
      role: 'admin',
    }));
  });

  it('只有擁有者可以讀取、更新或刪除申請', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'supply_requests/request-alice'), newRequest(ALICE));
    });

    await assertSucceeds(getDoc(doc(dbFor(ALICE), 'supply_requests/request-alice')));
    await assertFails(getDoc(doc(dbFor(BOB), 'supply_requests/request-alice')));
    await assertSucceeds(updateDoc(doc(dbFor(ALICE), 'supply_requests/request-alice'), {
      quantity: 4,
      status: 'cancelled',
    }));
    await assertFails(updateDoc(doc(dbFor(BOB), 'supply_requests/request-alice'), {
      quantity: 4,
    }));
    await assertFails(updateDoc(doc(dbFor(ALICE), 'supply_requests/request-alice'), {
      userId: BOB,
    }));
    await assertSucceeds(deleteDoc(doc(dbFor(ALICE), 'supply_requests/request-alice')));
    await assertFails(deleteDoc(doc(dbFor(BOB), 'supply_requests/request-alice')));
  });
});

describe('supply_pledges', () => {
  const newSupplyPledge = (uid) => ({
    userId: uid,
    userName: 'Someone',
    itemId: 'water',
    itemName: '礦泉水',
    unit: '瓶',
    qty: 3,
    status: 'pledged',
    pledgedAt: serverTimestamp(),
  });

  it('可以建立自己的物資認領', async () => {
    await assertSucceeds(
      setDoc(doc(dbFor(BOB), 'supply_pledges/pledge-bob'), newSupplyPledge(BOB)),
    );
  });

  it('不可冒用他人 userId 建立認領', async () => {
    await assertFails(
      setDoc(doc(dbFor(BOB), 'supply_pledges/pledge-fake'), newSupplyPledge(ALICE)),
    );
  });

  it('數量必須為正整數，且狀態必須從 pledged 開始', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), 'supply_pledges/pledge-zero'), {
      ...newSupplyPledge(BOB),
      qty: 0,
    }));
    await assertFails(setDoc(doc(dbFor(BOB), 'supply_pledges/pledge-status'), {
      ...newSupplyPledge(BOB),
      status: 'delivered',
    }));
  });

  it('建立時不可夾帶未定義欄位', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), 'supply_pledges/pledge-extra'), {
      ...newSupplyPledge(BOB),
      role: 'admin',
    }));
  });

  it('只有擁有者可以讀取、更新或刪除認領', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'supply_pledges/pledge-alice'), newSupplyPledge(ALICE));
    });

    await assertSucceeds(getDoc(doc(dbFor(ALICE), 'supply_pledges/pledge-alice')));
    await assertFails(getDoc(doc(dbFor(BOB), 'supply_pledges/pledge-alice')));
    await assertSucceeds(updateDoc(doc(dbFor(ALICE), 'supply_pledges/pledge-alice'), {
      qty: 4,
      status: 'delivered',
    }));
    await assertFails(updateDoc(doc(dbFor(BOB), 'supply_pledges/pledge-alice'), {
      qty: 4,
    }));
    await assertFails(updateDoc(doc(dbFor(ALICE), 'supply_pledges/pledge-alice'), {
      userId: BOB,
    }));
    await assertSucceeds(deleteDoc(doc(dbFor(ALICE), 'supply_pledges/pledge-alice')));
    await assertFails(deleteDoc(doc(dbFor(BOB), 'supply_pledges/pledge-alice')));
  });
});

describe('pledges', () => {
  const newPledge = (uid) => ({
    userId: uid,
    userName: 'Someone',
    itemId: 'water',
    itemName: '礦泉水',
    unit: '瓶',
    quantity: 3,
    status: 'pledged',
    pledgedAt: serverTimestamp(),
  });

  it('可以建立自己的認領', async () => {
    await assertSucceeds(setDoc(doc(dbFor(BOB), 'pledges/pledge-bob'), newPledge(BOB)));
  });

  it('不可冒用他人 userId 建立認領', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), 'pledges/pledge-fake'), newPledge(ALICE)));
  });

  it('建立時夾帶多餘欄位會被擋下', async () => {
    await assertFails(
      setDoc(doc(dbFor(BOB), 'pledges/pledge-bob'), { ...newPledge(BOB), role: 'admin' }),
    );
  });

  it('建立時 quantity 必須為正整數', async () => {
    await assertFails(
      setDoc(doc(dbFor(BOB), 'pledges/pledge-bob'), { ...newPledge(BOB), quantity: 0 }),
    );
    await assertFails(
      setDoc(doc(dbFor(BOB), 'pledges/pledge-bob'), { ...newPledge(BOB), quantity: '3' }),
    );
  });

  it('擁有者可以修改自己的認領數量', async () => {
    await assertSucceeds(
      updateDoc(doc(dbFor(ALICE), 'pledges/pledge-alice'), {
        quantity: 20,
        updatedAt: serverTimestamp(),
      }),
    );
  });

  it('★ 非擁有者不可修改他人的認領', async () => {
    await assertFails(
      updateDoc(doc(dbFor(BOB), 'pledges/pledge-alice'), {
        quantity: 20,
        updatedAt: serverTimestamp(),
      }),
    );
  });

  it('★ 非擁有者不可刪除他人的認領', async () => {
    await assertFails(deleteDoc(doc(dbFor(BOB), 'pledges/pledge-alice')));
  });

  it('擁有者可以刪除自己的認領', async () => {
    await assertSucceeds(deleteDoc(doc(dbFor(ALICE), 'pledges/pledge-alice')));
  });

  it('不可把他人的認領「轉手」給自己', async () => {
    await assertFails(updateDoc(doc(dbFor(BOB), 'pledges/pledge-alice'), { userId: BOB }));
  });

  it('擁有者也不可把自己的認領轉給別人', async () => {
    await assertFails(updateDoc(doc(dbFor(ALICE), 'pledges/pledge-alice'), { userId: BOB }));
  });

  it('未登入不可建立認領', async () => {
    await assertFails(setDoc(doc(anonDb(), 'pledges/pledge-anon'), newPledge(ALICE)));
  });
});

// 對應 supply_screen.dart 的 batch commit，保證紀錄與共用計數可原子寫入。
describe('物資申請與認領批次流程（supply_screen.dart）', () => {
  it('申請：建立 supply_requests + 累加 totalRequestedQty', async () => {
    const db = dbFor(BOB);
    const batch = writeBatch(db);
    batch.set(doc(db, 'supply_requests/request-bob'), {
      userId: BOB,
      userName: 'Bob',
      itemId: 'water',
      itemName: '礦泉水',
      unit: '瓶',
      quantity: 7,
      status: 'pending',
      createdAt: serverTimestamp(),
    });
    batch.update(doc(db, 'supply_items/water'), {
      totalRequestedQty: increment(7),
    });
    await assertSucceeds(batch.commit());
  });

  it('認領：建立 supply_pledges + 累加他人建立物資的 totalPledgedQty', async () => {
    const db = dbFor(BOB);
    const batch = writeBatch(db);
    batch.set(doc(db, 'supply_pledges/pledge-bob'), {
      userId: BOB,
      userName: 'Bob',
      itemId: 'water',
      itemName: '礦泉水',
      unit: '瓶',
      qty: 7,
      status: 'pledged',
      pledgedAt: serverTimestamp(),
    });
    batch.update(doc(db, 'supply_items/water'), {
      totalPledgedQty: increment(7),
    });
    await assertSucceeds(batch.commit());
  });
});

describe('health_reports（issue #33：Detail Tier 只給 Reporter 本人與救援者）', () => {
  const newReport = (uid) => ({
    id: `report-${uid}`,
    reporterId: uid,
    name: 'Someone',
    phone: '0900000000',
    bloodType: null,
    status: '輕傷',
    description: null,
    lat: 25.0,
    lng: 121.5,
    reportTime: new Date().toISOString(),
  });

  it('★ 一般註冊帳號不可讀取他人的回報', async () => {
    await assertFails(getDoc(doc(dbFor(BOB), 'health_reports/report-alice')));
  });

  it('★ 一般註冊帳號不可列出全部回報（health_screen 的互助任務查詢）', async () => {
    await assertFails(getDocs(query(
      collection(dbFor(BOB), 'health_reports'),
      where('status', 'in', ['輕傷', '重傷']),
    )));
  });

  it('未登入不可讀', async () => {
    await assertFails(getDoc(doc(anonDb(), 'health_reports/report-alice')));
  });

  it('Reporter 可讀自己的回報', async () => {
    await assertSucceeds(getDoc(doc(dbFor(ALICE), 'health_reports/report-alice')));
  });

  it('Reporter 可用 reporterId 條件查詢自己的回報', async () => {
    await assertSucceeds(getDocs(query(
      collection(dbFor(ALICE), 'health_reports'),
      where('reporterId', '==', ALICE),
    )));
  });

  it('★ 救援者可讀取他人的回報', async () => {
    await assertSucceeds(getDoc(doc(dbFor(RESCUER), 'health_reports/report-alice')));
  });

  it('★ 救援者可列出全部待救援回報（health_screen 的互助任務查詢）', async () => {
    await assertSucceeds(getDocs(query(
      collection(dbFor(RESCUER), 'health_reports'),
      where('status', 'in', ['輕傷', '重傷']),
    )));
  });

  it('可以建立自己的回報', async () => {
    await assertSucceeds(setDoc(doc(dbFor(BOB), 'health_reports/report-bob'), newReport(BOB)));
  });

  it('不可冒用他人 reporterId 建立回報', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), 'health_reports/report-fake'), newReport(ALICE)));
  });

  it('Reporter 不可把自己的回報轉手給他人（會連帶交出讀取權）', async () => {
    await assertFails(
      updateDoc(doc(dbFor(ALICE), 'health_reports/report-alice'), { reporterId: BOB }),
    );
  });

  it('Reporter 可更新自己回報的狀態', async () => {
    await assertSucceeds(
      updateDoc(doc(dbFor(ALICE), 'health_reports/report-alice'), { status: '輕傷' }),
    );
  });

  it('救援者不因身分而能竄改他人的回報', async () => {
    await assertFails(
      updateDoc(doc(dbFor(RESCUER), 'health_reports/report-alice'), { status: '安全' }),
    );
    await assertFails(deleteDoc(doc(dbFor(RESCUER), 'health_reports/report-alice')));
  });
});

describe('rescuers（issue #33：救援者身分不可自我提權）', () => {
  it('★ 使用者不可把自己登記為救援者', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), `rescuers/${BOB}`), { grantedAt: new Date() }));
  });

  it('★ 救援者也不可替他人授權', async () => {
    await assertFails(
      setDoc(doc(dbFor(RESCUER), `rescuers/${BOB}`), { grantedAt: new Date() }),
    );
  });

  it('救援者不可改動或撤銷自己的身分文件', async () => {
    await assertFails(updateDoc(doc(dbFor(RESCUER), `rescuers/${RESCUER}`), { extra: 1 }));
    await assertFails(deleteDoc(doc(dbFor(RESCUER), `rescuers/${RESCUER}`)));
  });

  it('★ 在 users 文件自填 role 不會取得救援者讀取權', async () => {
    const db = dbFor(BOB);
    await assertSucceeds(setDoc(doc(db, `users/${BOB}`), { id: BOB, role: 'rescuer' }));
    await assertFails(getDoc(doc(db, 'health_reports/report-alice')));
  });

  it('使用者可讀自己的救援者身分文件（供 App 判斷顯示）', async () => {
    await assertSucceeds(getDoc(doc(dbFor(RESCUER), `rescuers/${RESCUER}`)));
    await assertSucceeds(getDoc(doc(dbFor(BOB), `rescuers/${BOB}`)));
  });

  it('不可讀取他人的救援者身分文件', async () => {
    await assertFails(getDoc(doc(dbFor(BOB), `rescuers/${RESCUER}`)));
  });
});

describe('users（issue #33：個人檔案含 PII，只能讀自己的）', () => {
  it('★ 不可讀取他人的個人檔案', async () => {
    await assertFails(getDoc(doc(dbFor(BOB), `users/${ALICE}`)));
  });

  it('可讀自己的個人檔案（login / setup 流程）', async () => {
    await assertSucceeds(getDoc(doc(dbFor(ALICE), `users/${ALICE}`)));
  });

  it('可寫入自己的個人檔案（verify_email 流程）', async () => {
    await assertSucceeds(setDoc(doc(dbFor(BOB), `users/${BOB}`), { id: BOB, name: 'Bob' }));
  });

  it('不可寫入他人的個人檔案', async () => {
    await assertFails(setDoc(doc(dbFor(BOB), `users/${ALICE}`), { id: ALICE, name: '竄改' }));
  });

  it('未登入不可讀', async () => {
    await assertFails(getDoc(doc(anonDb(), `users/${ALICE}`)));
  });
});
