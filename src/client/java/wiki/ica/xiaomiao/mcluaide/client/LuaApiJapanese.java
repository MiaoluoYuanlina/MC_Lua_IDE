package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Map;

/** Japanese presentation only. Raw contracts stay intact for diagnostics, AI and fallback notes. */
final class LuaApiJapanese {
	private LuaApiJapanese() {}
	private static final Map<String, String> SUMMARIES = Map.ofEntries(
		Map.entry("MC.apiVersion", "MC Lua IDE のモッドバージョンを文字列で返します（例：\"1.0.12\"）。"),
		Map.entry("MC.sleep", "現在の処理だけを指定ミリ秒待機させます。他のコールバックとサーバーは動作し続けます。スクリプトを停止すると待機を取り消します。"),
		Map.entry("MC.Item.held", "オンラインのプレイヤーのメインハンドにあるアイテムと個数、コンポーネントを取得します。"),
		Map.entry("MC.Item.count", "インベントリ内の一致するアイテムを数えます。指定したコンポーネントのみ検査し、custom_data は NBT の部分一致で比較します。"),
		Map.entry("MC.Item.clear", "インベントリから条件に一致するアイテムを指定数まで削除します。custom_data は入れ子の部分一致に対応します。"),
		Map.entry("MC.Item.give", "指定プレイヤーにコンポーネント付きアイテムを与えます。入りきらない分はドロップします。"),
		Map.entry("MC.Item.validate", "サーバーのレジストリとコーデックを使い、アイテムスタックの定義を検証します。"),
		Map.entry("MC.Item.getSlot", "プレイヤーの指定インベントリスロットを取得します。番号は 0 始まりで、メニューのスロット番号とは異なります。"),
		Map.entry("MC.Item.setSlot", "指定インベントリスロットのアイテムを置き換え、クライアントに同期します。"),
		Map.entry("MC.Item.trade", "支払いと報酬をまとめて検証して反映します。アイテムや空き容量が不足した場合は何も変更しません。"),
		Map.entry("MC.Item.isOnCooldown", "インベントリ内の一致するアイテムが使用クールダウン中かを取得します。対象がなければ false を返します。"),
		Map.entry("MC.Item.startCooldown", "一致するアイテムの使用クールダウンを開始または再開始します。ticks 省略時は use_cooldown の時間、未設定なら 20 Tick を使用します。0 は即時終了です。"),
		Map.entry("MC.Item.setCooldown", "現在の残りクールダウンを呼び出し時点から指定 Tick に設定します。0 で即時終了します。将来の既定時間やアイテムのコンポーネントは変更しません。"),
		Map.entry("MC.Item.clearCooldown", "一致するアイテムのクールダウンを即時終了します。同じクールダウングループのアイテムにも影響します。"),
		Map.entry("MC.Player.list", "オンラインの全プレイヤーの名前と UUID を取得します。"),
		Map.entry("MC.Player.uuid", "オンラインのプレイヤー名から UUID を取得します。"),
		Map.entry("MC.Player.online", "指定 UUID のプレイヤーがオンラインかを確認します。"),
		Map.entry("MC.Player.position", "サーバー上のプレイヤーの座標、向き、ディメンションを取得します。"),
		Map.entry("MC.Player.facing", "プレイヤーの水平（yaw）と垂直（pitch）の向きの角度と、最も近い方角を取得します。"),
		Map.entry("MC.Player.lookDirection", "プレイヤーの 3D の視線方向を単位ベクトルで取得します。"),
		Map.entry("MC.Player.lookingAt", "プレイヤーのクロスヘアが指すブロック表面の正確な（小数を含む）座標を取得します。距離無制限も指定できます。"),
		Map.entry("MC.Player.relativePosition", "プレイヤーの前・後ろ・左・右の指定距離にある座標を、水平・垂直・両方の向き成分で計算します。"),
		Map.entry("MC.Player.teleport", "指定プレイヤーをテレポートします。ディメンション省略時は現在のディメンションを維持します。"),
		Map.entry("MC.Player.openChest", "実際のブロックを必要としないチェスト UI を開きます。1～6 行に対応します。読み取り専用の店には onContainerClicked を使用します。"),
		Map.entry("MC.Player.openBlock", "実在するブロックのバニラメニューを開きます。通常の距離とディメンション制限は適用されます。"),
		Map.entry("MC.Player.openCrafting", "実際のブロックを必要とせず作業台を開きます。"),
		Map.entry("MC.Player.openEnderChest", "対象プレイヤー自身のエンダーチェストを開きます。"),
		Map.entry("MC.Player.setMenuItem", "現在開いているメニューのスロットを書き換えます。仮想チェストにも対応し、expectedContainerId でメニューの変更を検出できます。"),
		Map.entry("MC.Player.setCarried", "プレイヤーがマウスカーソルに持っているアイテムを変更します。メインハンドとは異なります。"),
		Map.entry("MC.Block.get", "読み込み済みの実ブロック、ブロックステート、ブロックエンティティのデータを取得します。チャンクを読み込んだりワールドを変更したりしません。"),
		Map.entry("MC.Block.set", "ブロックを設置します。states、blockEntityData、itemComponents を指定できます。"),
		Map.entry("MC.Block.fill", "サーバーで範囲内のブロックを直接置き換えます。バニラの fill コマンドの制限は変更しません。"),
		Map.entry("MC.Entity.spawn", "エンティティを生成し、実行時 UUID を返します。スポーンエッグではなくエンティティの種類 ID を渡してください。"),
		Map.entry("MC.Entity.ride", "最初のエンティティを次のエンティティに乗せます。プレイヤーも乗客にできます。自分自身・循環・別ディメンションへの騎乗はできません。"),
		Map.entry("MC.Entity.dismount", "指定エンティティを乗り物から降ろします。"),
		Map.entry("MC.Event.onInterval", "指定ミリ秒間隔でコールバックを実行します。各コールバックはスクリプトの変数を共有します。"),
		Map.entry("MC.Event.onBlockBroken", "プレイヤーがブロックの破壊に成功したイベントを登録します。プレイヤー UUID、元のブロック ID、座標、ディメンションを受け取ります。"),
		Map.entry("MC.Event.onPlayerDied", "プレイヤーの死亡を監視します。プレイヤー UUID と死亡位置を受け取ります。"),
		Map.entry("MC.Event.onPlayerRespawned", "プレイヤーのリスポーンを監視します。alive を調べて死亡後の復活かどうかを確認してください。"),
		Map.entry("MC.Event.onScriptStarted", "スクリプト開始時のコールバックを登録します。"),
		Map.entry("MC.Event.onScriptStopped", "スクリプトの終了・停止時の後処理を登録します。"),
		Map.entry("MC.GameRule.set", "ゲームルールを変更します。最初の引数はプレイヤー UUID ではなくルール ID です。"),
		Map.entry("MC.Sound.play", "サウンドを再生します。座標 3 個がすべて nil の場合は位置に依存せず再生します。dimension=nil は全ディメンションが対象です。途中再生にはクライアント Mod と必須クライアントモードが必要です。"),
		Map.entry("MC.Chat.broadcast", "全プレイヤーに文字列または Minecraft テキストコンポーネントを送信します。"),
		Map.entry("MC.Chat.send", "オンラインの指定プレイヤーに文字列またはテキストコンポーネントを送信します。")
	);
	static String summary(String signature) {
		String symbol = LuaApiEnglish.symbol(signature);
		String translated = SUMMARIES.get(symbol);
		if (translated != null) return translated;
		if (symbol.startsWith("MC.Event.on")) return "イベント「" + symbol.substring("MC.Event.on".length()) + "」のコールバックを登録します。スクリプトを停止すると登録を解除します。";
		if (symbol.endsWith(".name")) return "指定言語の表示名を取得します。翻訳がない場合はフォールバックし、実際に使用した言語も返します。";
		if (symbol.endsWith(".random")) return "現在のレジストリからランダムな ID を取得します。読み込まれた Mod の ID も対象になります。";
		return "サーバー API「" + symbol + "」。引数の形式、注意事項、使用例を確認してください。";
	}
	static String returns(String raw) {
		String english = LuaApiEnglish.returns(raw);
		String suffix = "; nil, reason for a recoverable failure";
		boolean nullable = english.endsWith(suffix);
		String base = nullable ? english.substring(0, english.length() - suffix.length()) : english;
		return LuaUiText.localizeEnglish(base) + (nullable ? "。回復可能な失敗では nil, 理由を返します" : "");
	}
	static String parameter(String name, String signature) {
		String english = LuaApiEnglish.parameter(name, signature);
		String hint = english.substring(english.indexOf(" - ") + 3);
		return name + " - " + LuaUiText.localizeEnglish(hint);
	}
	static String details(String original, boolean clientRequired) {
		return (clientRequired ? "対象クライアントの互換 Mod と、サーバーの必須クライアントモードが必要です。\n" : "")
				+ "サーバー権限で実行します。チャンク未読込・オフライン・対象エンティティ不在などの回復可能な失敗は nil, 理由と警告を返します。引数・権限・不明な ID などのエラーは引き続き報告します。\n\n詳細の原文（中国語）：\n" + original;
	}
}
