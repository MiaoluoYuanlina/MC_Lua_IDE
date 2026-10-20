package wiki.ica.xiaomiao.mcluaide.server;

import wiki.ica.xiaomiao.mcluaide.network.LuaProtocol;

/**
 * Freezes the wire numbering of LuaProtocol.Action. The number sent on the wire is the declaration position, so a
 * reordered or inserted constant would make an updated client and an older server silently disagree. Append only.
 */
public final class LuaProtocolStabilitySmoke {
	private static final String[] FROZEN = { "ACCESS", "LIST", "READ", "SAVE", "DELETE", "RUN", "STOP", "GET_LIMITS", "SAVE_LIMITS", "GET_API_DOCUMENTATION", "GET_COMPONENT_EDITOR_DATA", "VALIDATE_ITEM_COMPONENTS", "GET_REGISTRY_CATALOG", "CREATE_DIRECTORY", "DELETE_DIRECTORY", "LIST_DATAPACKS", "CREATE_DATAPACK", "READ_DATAPACK_FILE", "SAVE_DATAPACK_FILE", "DELETE_DATAPACK_PATH", "CREATE_DATAPACK_DIRECTORY", "RENAME_DATAPACK_PATH", "CREATE_DATAPACK_FILE", "GET_DIMENSION_JSON", "VALIDATE_DIMENSION_JSON", "GET_DATAPACK_JSON_OPTIONS", "GET_DATAPACK_JSON_SOURCE", "VALIDATE_DATAPACK_JSON", "GET_DATAPACK_JSON_CATEGORIES", "RUN_FUNCTION", "GET_GIT_CONFIG", "SAVE_GIT_CONFIG", "GIT_PUSH", "READ_DATAPACK_PREVIEW", "GET_DEBUG_VARIABLES", "RUN_NORMAL", "SET_BREAKPOINTS", "GET_DEBUG_STATE", "DEBUG_CONTROL", "SET_DEBUG_GAME_PAUSE", "SET_DEBUG_PLAYER_MOVEMENT", "DEBUG_CAMERA_MODE", "RELOAD_DATAPACKS", "GET_SERVER_PACK", "BEGIN_SERVER_PACK_UPLOAD", "SERVER_PACK_UPLOAD_CHUNK", "FINISH_SERVER_PACK_UPLOAD", "CANCEL_SERVER_PACK_UPLOAD", "SET_SERVER_PACK", "GET_GIT_HISTORY", "GET_GIT_DETAIL", "GIT_COMMIT", "GIT_RESTORE", "GITHUB_ACCOUNT", "GITHUB_CLIENT_ID", "GITHUB_LOGIN", "GITHUB_POLL", "GITHUB_LOGOUT", "GITHUB_REPOSITORIES", "GITHUB_SELECT_REPOSITORY", "GITHUB_CREATE_REPOSITORY", "GET_MCP_SERVER", "SET_MCP_SERVER", "CLEAR_MCP_SERVER_LOGS", "MCP_TOOL" };

	public static void main(String[] args) {
		var actions = LuaProtocol.Action.values();
		if (actions.length < FROZEN.length)
			throw new AssertionError("A protocol action was removed: expected at least " + FROZEN.length + " but found " + actions.length);
		for (int index = 0; index < FROZEN.length; index++) {
			if (!actions[index].name().equals(FROZEN[index]) || actions[index].id() != index || LuaProtocol.Action.byId(index) != actions[index])
				throw new AssertionError("Protocol action #" + index + " changed from " + FROZEN[index] + " to " + actions[index].name()
						+ "; only append new actions at the end");
		}
		System.out.println("Protocol stability smoke passed: " + FROZEN.length + " frozen action ids (" + (actions.length - FROZEN.length) + " appended)");
	}
}