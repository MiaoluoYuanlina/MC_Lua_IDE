<p align="center"><img src="src/main/resources/assets/mc_lua_ide/icon.png" width="128" alt="MC Lua IDE アイコン"></p>

<h1 align="center">MC Lua IDE</h1>

<p align="center">Minecraft サーバーで Lua スクリプトを動かし、管理者向けの本格的な IDE をゲーム内で使えます。</p>

<p align="center"><a href="README.md">English</a> · <a href="README.zh_cn.md">简体中文</a> · 日本語</p>

---

## 概要

MC Lua IDE は **Minecraft 26.1.2** 向けの Fabric Mod です。サーバー管理者は、ゲームの実行中に**サーバー上で動く** Lua スクリプトを書き、ゲーム内の IDE（エディター、デバッガー、API リファレンス、ターミナル、データパック・リソースパック用ツール）でそのまま扱えます。

- **スクリプトはサーバーで動きます。** ワールドの読み書き、イベントへの反応、プレイヤーとのやり取り、メニューの表示ができます。一般プレイヤーは何も導入する必要がありません。
- **IDE は管理者のクライアントで動きます。** ゲーム中に **U** キーで開きます。使えるのはサーバー所有者（OP レベル 4、またはシングルプレイのホスト）だけで、Mod を入れても権限は与えられません。
- **初期状態で安全です。** スクリプトは時間・出力・イベント数に制限のあるサンドボックスで動きます。危険な機能（Java へのアクセス、ファイル・OS へのアクセス、サーバーターミナル、AI ツール向けの MCP）は、サーバー所有者が有効にするまで無効です。

```lua
-- 参加したプレイヤーにあいさつします。
MC.Event.onPlayerJoined(function(event)
    MC.Chat.message(event.playerUuid, {text = "ようこそ、" .. event.playerName .. " さん！", color = "#55FF55"})
end)
```

## 主な機能

| 分野 | 内容 |
| --- | --- |
| エディター | Lua・JSON・mcfunction のハイライトとリアルタイム診断、API・変数・レジストリ ID（アイテム、ブロック、サウンドなど）の補完、正規表現対応の検索・置換、ファイルをまたいだ定義へ移動・参照検索・名前変更、アウトライン、スニペット、複数カーソル、ミニマップ、分割エディター、ファイル比較、ファイル内検索、コマンドパレット |
| デバッガー | 条件・ヒット回数・ログメッセージ付きブレークポイント、例外ブレークポイント、ステップイン / オーバー / アウト、カーソルまで実行、コールスタック・ローカル変数・ウォッチ・ホバー表示・コルーチン、停止中のワールド凍結（任意） |
| Minecraft API | プレイヤー、アイテムとデータコンポーネント、ブロック、エンティティ、イベント、タイマー、チャットと JSON テキスト、タイトル、サウンド、パーティクル、仮想チェストメニュー、コマンド、データファイル、リソースパック。IDE 内に例付きの完全なリファレンスがあります |
| データパック・リソースパック | スクリプトと一緒に編集: ゲーム内の例を使う JSON エディター、レイヤー付き PNG ペイント、サウンド・構造物のプレビュー、ワンクリック再読み込み |
| ターミナル | ローカルのシェル（PowerShell、cmd、bash など）と、任意のサーバーシェル |
| 共同作業と安全 | 編集中の表示、競合チェック付き保存、監査ログ、スクリプトの自動スナップショットと復元、設定のエクスポート / インポート、Git 履歴 |
| 拡張 | JSON マニフェストでスニペットやコマンドを追加。ほかの Mod が Lua 関数、IDE ウィンドウ、コマンド、補完を追加できる Java API |

インターフェースとマニュアルは中国語・英語・日本語に対応しています（ゲームの言語に従います）。

## はじめに

動作環境: Minecraft 26.1.2、Fabric Loader 0.19.3 以上、Fabric API 0.155.2 以上、Java 25。この Mod と Fabric API を、サーバーと IDE を使う管理者のクライアントの `mods/` に入れます（シングルプレイでは自分のクライアントだけ）。

1. ワールドに入って **U** を押します。初回は**ようこそ**ページが表示され、`examples/` にサンプルプロジェクトを作成できます。
2. **ファイル → Lua ファイル** を開き、**Ctrl+N** でスクリプトを作成します。
3. コードを書いて **Ctrl+S** で保存、**Ctrl+Enter** で実行します。出力は**実行ログ**に表示されます。

## 使い方

### スクリプトを書く

スクリプトはサーバーの `config/mc_lua_ide/scripts/` に保存されます（サブフォルダーも使えます）。API はすべて `MC` テーブルの下にあり、**API リファレンス**ウィンドウ（**Ctrl+F1**）で全関数・引数・例を確認できます。入力中は補完が関数・引数・ID を提案します。

```lua
-- 最初のオンラインプレイヤーにダイヤモンドを 3 個渡し、位置を表示します。
local player = MC.Player.list()[1]
if player then
    local pos = MC.Player.position(player.uuid)
    print(player.name, pos.x, pos.y, pos.z)
    MC.Item.give(player.uuid, "minecraft:diamond", 3)
end

-- ブロックを置きます（x, y, z, ブロック ID）。
MC.Block.set(0, 64, 0, "minecraft:gold_block")
```

**イベントと時間**: リスナーを登録したスクリプトは、停止するまで動き続けます。`MC.sleep` はそのスクリプトだけを一時停止し、ゲームは止めません。

```lua
MC.Event.onBlockPlaced(function(event)
    print(event.playerName, "が", event.blockId, "を設置:", event.x, event.y, event.z)
end)

MC.Event.onInterval(1000, function(event)
    print("1 秒経過", event.elapsedMs)
end)

MC.Event.onScriptStopped(function()
    MC.Chat.broadcast({text = "スクリプトを停止しました", color = "#FF5555"})
end)
```

**モジュール**: コードを複数のファイルに分け、`require` で読み込みます。モジュールはテーブルを返します。

```lua
-- lib/greeting.lua
local M = {}
function M.greet(name) return "こんにちは、" .. name .. " さん！" end
return M

-- main.lua
local greeting = require("lib.greeting")
print(greeting.greet("Steve"))
```

**実行**: IDE の**実行**ボタンか **Ctrl+Enter**、またはコマンドを使います（コンソールやコマンドブロックからも使えます）。

| コマンド | 内容 |
| --- | --- |
| `/lua run <スクリプト>` | スクリプトを実行 |
| `/lua testrun <スクリプト>` | デバッグ付きで実行（ブレークポイント、変数） |
| `/lua stop` | 実行中のスクリプトを停止し、リスナーを解除 |
| `/lua list` | スクリプトの一覧 |

### IDE

- **ウィンドウ**: すべてのツールはドッキングできるウィンドウです。タイトルをドラッグしてドッキング・分割・タブ化でき、レイアウトは記憶されます。**UI とウィンドウ → 表示** から任意のツールを開けます。
- **コマンドパレット（Ctrl+Shift+P）**: すべての機能を名前で探せます。**Ctrl+P** でファイルを開き、**Ctrl+Shift+O** で関数へ移動、**:** と数字で行へ移動します。
- **よく使うショートカット:**

| キー | 操作 |
| --- | --- |
| Ctrl+S · Ctrl+Enter | 保存 · 実行 / 停止 |
| Ctrl+F · Ctrl+H · Ctrl+Shift+F | 検索 · 置換 · ファイル内検索 |
| Ctrl+B または Ctrl+クリック · Ctrl+Shift+B · F2 | 定義へ移動 · 参照検索 · 名前変更 |
| Ctrl+D · Alt+クリック | 次の同じ箇所を選択 · カーソルを追加 |
| Ctrl+I | カーソル位置に ID やデータコンポーネントを挿入 |
| Tab | 補完を確定 / スニペットを展開（`fn`、`for`、`if`、`pcall` など） |
| Ctrl+/ | コメントの切替 |
| Ctrl+` | ターミナル |
| F12 | デバッグ設定とショートカット |

- **個人設定**: 配色、フォント、カーソル、背景画像、構文の色、メニューをウィンドウのタイトルバーに統合するオプション。

### デバッグ

1. 行番号をクリックしてブレークポイントを設定します。右クリックで**条件**、**ヒット回数**、**ログメッセージ**（ログポイントは停止せずに出力だけします）を設定できます。
2. メニューバーの**デバッグ**にチェックを入れて（または `/lua testrun` で）実行します。
3. 停止したら: ステップイン / オーバー / アウト、カーソルまで実行、コールスタックと変数の確認、**ウォッチ**の追加、変数にマウスを重ねて値を表示できます。

デバッガーウィンドウでは、**エラー**（捕捉されないものだけ、またはすべて）が発生した行で停止することもでき、スクリプトの**コルーチン**一覧も表示されます。停止中にワールドを凍結することもできます。

### データパック・リソースパック

**ファイル → データパック / リソースパック** でそれぞれのワークスペースを開きます。JSON ファイルには、実行中のゲームから取ったテンプレートと例を使う構造化エディターがあります。PNG テクスチャはレイヤー付きのペイントツールで開き、サウンドと構造物はプレビューできます。保存後は**ツール**からデータパックやリソースパックを再読み込みします。

### ターミナル

**Ctrl+`** でターミナルのタブを開き、自分のコンピューターのシェルを実行します。色、マウス、全画面のプログラムに対応しています。**サーバーターミナル**（サーバー機上のシェル）はサーバー所有者向けで、`config/mc_lua_ide/terminal.properties` で有効にするまで使えません。

### 共同作業と安全

- ほかの管理者が同じファイルを開いていると、エディターに表示されます。他人の新しい版を上書きしてしまう保存は拒否され、比較・上書き・相手の版の読み込みを選べます。
- **監査ログ**には、誰が何を保存・実行・変更したかが記録されます。
- **バックアップ**: スクリプトに変更があると、サーバーが 30 分ごとにスクリプトフォルダーのスナップショットを作成し、どのスナップショットからでも復元できます（復元も元に戻せます）。IDE の設定はエクスポート・インポートできます。

### 設定ファイル

すべて `config/mc_lua_ide/` にあります（特記のないものはサーバー側）:

| ファイル | 用途 |
| --- | --- |
| `scripts/` | Lua スクリプト |
| `lua-limits.properties` | 実行の制限と、許可する危険なライブラリ（IDE からも変更可能） |
| `terminal.properties` | サーバーターミナル（既定で無効） |
| `snapshots.properties` · `snapshots/` | スクリプトの自動スナップショット |
| `audit.log` | 監査ログ |
| `extensions/` | 自分用のスニペット・コマンドのマニフェスト（クライアント） |
| `personalization.properties`、`sessions/` | IDE の外観とサーバーごとのレイアウト（クライアント） |

## ドキュメント

- [ゲーム内マニュアル](docs/manual/ja_jp.md): Lua API と例（[English](docs/manual/en_us.md) · [中文](docs/manual/zh_cn.md)）。IDE 内でも読めます。
- [IDE ガイド](docs/guide/ide-guide.md)（英語）: IDE の全機能の詳細。
- [IDE の拡張](docs/extensions/overview.md)（英語）: マニフェストとほかの Mod 向け Java API。
- [変更履歴](CHANGELOG.md)

## ソースからのビルド

```bash
./gradlew build
```

jar は `build/libs/` に出力されます。`build` はスモークテストも実行します（`PATH` に Git が必要です）。

## 状況

Windows でテスト済みです。macOS と Linux でのローカルターミナルとタイトルバー統合は、実機ではまだテストしていません。不具合の報告や提案は [Issues](https://github.com/MiaoluoYuanlina/MC_Lua_IDE/issues) へどうぞ。

## ライセンス

[GPL-3.0](LICENSE)。Copyright (c) 2026 XiaoMiao_ICa。同梱ライブラリはそれぞれの（GPL と互換性のある）ライセンスに従います: [LuaJ](https://github.com/luaj/luaj)（MIT）、[imgui-java](https://github.com/SpaiR/imgui-java) と [Dear ImGui](https://github.com/ocornut/imgui)（MIT）、[JNA](https://github.com/java-native-access/jna)（Apache 2.0 / LGPL 2.1）。
