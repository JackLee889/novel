package com.java2nb.common.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * 查询参数
 */
public class Query extends LinkedHashMap<String, Object> {
	private static final long serialVersionUID = 1L;
	// 
	private int offset;
	// 每页条数
	private int limit;

	public Query(Map<String, Object> params) {
		this.putAll(params);
		Object sort = params.get("sort");
		if (sort != null && !sort.toString().isBlank()
				&& !sort.toString().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
			throw new IllegalArgumentException("无效的排序字段");
		}
		Object direction = params.get("order");
		String order = direction == null ? "desc" : direction.toString().toLowerCase(Locale.ROOT);
		if (!order.equals("asc") && !order.equals("desc")) {
			throw new IllegalArgumentException("无效的排序方向");
		}
		this.put("order", order);
		// 分页参数
		this.offset = Integer.parseInt(params.get("offset").toString());
		this.limit = Integer.parseInt(params.get("limit").toString());
		if (offset < 0 || limit <= 0 || limit > 1000) {
			throw new IllegalArgumentException("无效的分页参数");
		}
		this.put("offset", offset);
		this.put("page", offset / limit + 1);
		this.put("limit", limit);
	}

	public int getOffset() {
		return offset;
	}

	public void setOffset(int offset) {
		this.put("offset", offset);
	}

	public int getLimit() {
		return limit;
	}

	public void setLimit(int limit) {
		this.limit = limit;
	}
}
