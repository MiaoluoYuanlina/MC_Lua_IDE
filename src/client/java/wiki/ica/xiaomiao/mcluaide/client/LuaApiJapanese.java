package wiki.ica.xiaomiao.mcluaide.client;

import java.util.Map;

/** Japanese presentation only. Raw contracts stay intact for diagnostics, AI and fallback notes. */
final class LuaApiJapanese {
	private LuaApiJapanese() {}
	private static final Map<String, String> SUMMARIES = Map.ofEntries(
		Map.entry("MC.apiVersion", "MC Lua IDE のモッドバージョンを文字列で返します（例：\"1.0.12\"）。"),
		Map.entry("MC.Item.setSlotComponents", "インベントリスロットの既存アイテムのコンポーネントだけを変更します（種類と個数はそのまま）。!minecraft:xxx = {} で削除できます。"),
		Map.entry("MC.Entity.setEquipmentComponents", "装備スロットの既存アイテムのコンポーネントだけを変更します（種類と個数はそのまま）。"),
		Map.entry("MC.Player.movingForward", "プレイヤーが前進キー（W）を押しているかを、クライアントが最後に報告した入力で確認します。"),
		Map.entry("MC.sleep", "現在の処理を指定ミリ秒（0～3600000）待機させます。サーバーと他のコールバックは動作し続けます。"),
		Map.entry("MC.Item.held", "プレイヤーのメインハンドのアイテムとコンポーネントを取得します。"),
		Map.entry("MC.Item.count", "インベントリ内の一致するアイテムを数えます。指定したコンポーネントのみ検査し、custom_data は NBT の部分一致です。"),
		Map.entry("MC.Item.clear", "インベントリから一致するアイテムを削除します。指定したコンポーネントのみ検査し、コンポーネント指定時はアイテム ID が必須です。"),
		Map.entry("MC.Item.give", "指定プレイヤーにコンポーネント付きアイテムを与えます。入りきらない分はドロップします。"),
		Map.entry("MC.Item.validate", "アイテムスタックを検証して正規化します。ゲームは変更しません。"),
		Map.entry("MC.Item.getSlot", "インベントリスロットのアイテムとコンポーネントを取得します。番号は 0 始まり（ホットバー 0～8）です。"),
		Map.entry("MC.Item.setSlot", "プレイヤーのインベントリスロットのアイテムを置き換えます。"),
		Map.entry("MC.Item.trade", "アトミックな店の取引です。支払いと報酬を先に検証し、不足時は何も変更しません。"),
		Map.entry("MC.Item.isOnCooldown", "インベントリ内の一致するアイテムがクールダウン中かを確認します。コンポーネントで絞り込めます。"),
		Map.entry("MC.Item.startCooldown", "一致するアイテムのクールダウンを開始または再開始します。ticks 省略時はアイテム自身の時間、0 で即時終了します。"),
		Map.entry("MC.Item.setCooldown", "一致するアイテムの残りクールダウンを ticks に設定し、今から計測します。0 で即時終了します。"),
		Map.entry("MC.Item.clearCooldown", "プレイヤーのアイテムのクールダウンを即時終了します。"),
		Map.entry("MC.Player.list", "オンラインの全プレイヤーの名前と UUID を取得します。"),
		Map.entry("MC.Player.uuid", "オンラインのプレイヤー名から UUID を取得します。"),
		Map.entry("MC.Player.online", "指定 UUID のプレイヤーがオンラインかを確認します。"),
		Map.entry("MC.Player.position", "サーバー上のプレイヤーの座標、向き、ディメンションを取得します。"),
		Map.entry("MC.Player.facing", "プレイヤーの yaw と pitch の角度と、最も近い方角を取得します。"),
		Map.entry("MC.Player.lookDirection", "プレイヤーの 3D の視線方向を単位ベクトルで取得します。"),
		Map.entry("MC.Player.lookingAt", "クロスヘアが指すブロック表面の正確な座標を取得します。距離無制限も指定できます。"),
		Map.entry("MC.Player.relativePosition", "プレイヤーから指定距離の座標を計算します。"),
		Map.entry("MC.Player.carried", "プレイヤーのカーソルに持っているアイテムを読み取ります。"),
		Map.entry("MC.Player.teleport", "オンラインのプレイヤーをテレポートします。ディメンション省略時は現在のものを維持します。"),
		Map.entry("MC.Player.openChest", "ワールドに存在しないチェスト UI を開きます。items は配列、rows は 1～6、readOnly でアイテム移動を禁止します。内容は保存されません。"),
		Map.entry("MC.Player.openBlock", "ブロックのバニラメニューを開きます。"),
		Map.entry("MC.Player.openCrafting", "実際のブロックなしで作業台を開きます。"),
		Map.entry("MC.Player.openEnderChest", "対象プレイヤー自身のエンダーチェストを開きます。"),
		Map.entry("MC.Player.setMenuItem", "現在のメニューのスロットを置き換えます。仮想チェストにも対応し、itemId=nil で空にします。"),
		Map.entry("MC.Player.setCarried", "カーソルに持っているアイテムを設定または空にします。"),
		Map.entry("MC.Block.get", "実ブロックの ID、ステート、ブロックエンティティのデータを取得します。チャンクの読み込みやワールドの変更はしません。"),
		Map.entry("MC.Block.set", "ブロックを設置します。states と blockEntityData を指定できます。"),
		Map.entry("MC.Block.fill", "範囲を直接埋めます（バニラの上限なし）。keepExisting は空気のみ、dropItems は置換したブロックのドロップを発生させます。"),
		Map.entry("MC.Entity.spawn", "エンティティを生成して UUID を返します。スポーンエッグではなく種類 ID を渡します。"),
		Map.entry("MC.Entity.ride", "最初のエンティティを次のエンティティに乗せます。force の既定は true です。存在しない場合は nil, 理由を返します。"),
		Map.entry("MC.Entity.dismount", "指定エンティティを乗り物から降ろします。"),
		Map.entry("MC.Event.onInterval", "指定ミリ秒ごとにコールバックを実行します。スクリプトの停止まで続きます。"),
		Map.entry("MC.Event.onBlockBroken", "プレイヤーのブロック破壊を監視します（プレイヤー UUID、ブロック ID、座標、ディメンション）。"),
		Map.entry("MC.Event.onPlayerDied", "プレイヤーの死亡を監視します。プレイヤー UUID と死亡位置を受け取ります。"),
		Map.entry("MC.Event.onPlayerRespawned", "プレイヤーのリスポーンを監視します。alive で死亡後の復活かを確認します。"),
		Map.entry("MC.Event.onScriptStarted", "スクリプト開始時のコールバックを登録します。"),
		Map.entry("MC.Event.onScriptStopped", "スクリプト停止時に実行する後処理を登録します。"),
		Map.entry("MC.GameRule.set", "ゲームルールを変更します。最初の引数はルール ID です。"),
		Map.entry("MC.Sound.play", "サウンドを再生します。座標が nil なら位置に依存せず、dimension=nil は全ディメンションに再生します。途中再生にはクライアント Mod が必要です。"),
		Map.entry("MC.Chat.broadcast", "全プレイヤーに文字列またはテキストコンポーネントを送信します。"),
		Map.entry("MC.Chat.send", "オンラインのプレイヤーに文字列またはテキストコンポーネントを送信します。")
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
