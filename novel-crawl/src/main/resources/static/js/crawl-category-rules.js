(function (root, factory) {
    var api = factory();
    if (typeof module === "object" && module.exports) {
        module.exports = api;
    } else {
        root.CrawlCategoryRules = api;
    }
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    function cloneRule(rule) {
        if (rule == null) {
            return {};
        }
        if (typeof rule !== "object" || Array.isArray(rule)) {
            throw new Error("采集规则格式不正确");
        }
        return JSON.parse(JSON.stringify(rule));
    }

    function uniqueValues(values) {
        var result = [];
        values.forEach(function (value) {
            if (typeof value !== "string") {
                throw new Error("分类ID规则格式不正确");
            }
            value = value.trim();
            if (value && result.indexOf(value) < 0) {
                result.push(value);
            }
        });
        return result;
    }

    function parseValues(text) {
        var values = [];
        String(text || "").split(/\r\n|\n|\r/).forEach(function (line) {
            line = line.trim();
            // A category can be a complete or relative URL. Its query commas
            // are data; URLs are entered one per line instead of comma separated.
            if (/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(line) || /^\//.test(line) || /[?#]/.test(line)) {
                values.push(line);
            } else {
                values = values.concat(line.split(/[,，]/));
            }
        });
        return uniqueValues(values);
    }

    function valuesFor(rule, key) {
        rule = rule || {};
        if (rule.catIdRules && Object.prototype.hasOwnProperty.call(rule.catIdRules, key)) {
            if (!Array.isArray(rule.catIdRules[key])) {
                throw new Error("分类ID列表格式不正确");
            }
            return uniqueValues(rule.catIdRules[key]);
        }
        return parseValues(rule.catIdRule && rule.catIdRule[key]);
    }

    function normalizeCategories(categories) {
        if (!Array.isArray(categories) || categories.length === 0) {
            throw new Error("本站分类字典为空，请检查分类配置后刷新页面");
        }
        var seen = {};
        return categories.map(function (category) {
            if (!category || !/^[1-9]\d*$/.test(String(category.id)) ||
                typeof category.name !== "string" || !category.name.trim() || seen[category.id]) {
                throw new Error("本站分类字典格式不正确，请刷新页面重试");
            }
            seen[category.id] = true;
            return {id: String(category.id), name: category.name, workDirection: category.workDirection};
        });
    }

    function writeCategories(rule, categories, fieldValues) {
        var result = cloneRule(rule);
        var legacy = cloneRule(result.catIdRule);
        var lists = cloneRule(result.catIdRules);
        normalizeCategories(categories).forEach(function (category) {
            var key = "catId" + category.id;
            var values = parseValues(fieldValues[key]);
            if (values.length) {
                legacy[key] = values[0];
                lists[key] = values;
            } else {
                delete legacy[key];
                delete lists[key];
            }
        });
        if (!Object.keys(legacy).length && !Object.keys(lists).length) {
            throw new Error("分类ID至少要填一项");
        }
        result.catIdRule = legacy;
        result.catIdRules = lists;
        return result;
    }

    function writeBookStatuses(originalRule, originalFields, editedFields) {
        var result = cloneRule(originalRule);
        var changed = [0, 1].filter(function (status) {
            return editedFields[status] !== originalFields[status];
        });
        if (!changed.length) {
            return result;
        }
        if (editedFields[0] && editedFields[0] === editedFields[1]) {
            throw new Error("连载和完结的状态文本不能相同");
        }
        // A site can have several aliases for one status. Preserve all aliases
        // for untouched inputs; only replace the status the operator changed.
        Object.keys(result).forEach(function (label) {
            if (changed.indexOf(result[label]) >= 0) {
                delete result[label];
            }
        });
        changed.forEach(function (status) {
            var label = editedFields[status];
            if (!label) {
                return;
            }
            if (Object.prototype.hasOwnProperty.call(result, label) && result[label] !== status) {
                throw new Error("状态文本与其他状态的已有别名冲突：" + label);
            }
            result[label] = status;
        });
        return result;
    }

    function createEditor(document, container, status, onReady) {
        var categories = null;
        var rule = {};
        var inputs = {};
        var ready = false;

        function changeReady(value, message) {
            ready = value;
            status.textContent = message;
            Object.keys(inputs).forEach(function (key) { inputs[key].disabled = !value; });
            if (onReady) {
                onReady(value);
            }
        }

        function fill() {
            Object.keys(inputs).forEach(function (key) {
                inputs[key].value = valuesFor(rule, key).join("\n");
            });
        }

        var editor = {
            isReady: function () { return ready; },
            setRule: function (value) {
                rule = cloneRule(value);
                try {
                    fill();
                } catch (error) {
                    changeReady(false, error.message);
                    throw error;
                }
            },
            setCategories: function (value) {
                try {
                    categories = normalizeCategories(value);
                    while (container.firstChild) {
                        container.removeChild(container.firstChild);
                    }
                    inputs = {};
                    categories.forEach(function (category) {
                        var key = "catId" + category.id;
                        var item = document.createElement("li");
                        var label = document.createElement("label");
                        label.htmlFor = key;
                        label.textContent = category.name + "（本站分类ID：" + category.id + "）";
                        var input = document.createElement("textarea");
                        input.id = key;
                        input.name = key;
                        input.rows = 2;
                        input.cols = 52;
                        input.placeholder = "远端分类ID：多个ID用逗号或换行分隔；完整URL每行一个";
                        item.appendChild(label);
                        item.appendChild(document.createElement("br"));
                        item.appendChild(input);
                        container.appendChild(item);
                        inputs[key] = input;
                    });
                    fill();
                    changeReady(true, "已加载 " + categories.length + " 个本站分类；留空的分类不参与采集。");
                } catch (error) {
                    changeReady(false, error.message);
                    throw error;
                }
            },
            fail: function (message) {
                changeReady(false, message || "本站分类加载失败，请刷新页面重试，当前不能保存。");
            },
            collect: function (baseRule) {
                if (!ready || !categories) {
                    throw new Error("本站分类尚未成功加载，请刷新页面后再提交");
                }
                var fields = {};
                Object.keys(inputs).forEach(function (key) { fields[key] = inputs[key].value; });
                return writeCategories(baseRule, categories, fields);
            }
        };
        changeReady(false, "正在加载本站分类…");
        return editor;
    }

    function loadCategories(editor, request) {
        request("/crawl/listBookCategories", function (response) {
            if (!response || response.code !== 200) {
                editor.fail("本站分类加载失败，请刷新页面重试，当前不能保存。");
                return;
            }
            try {
                editor.setCategories(response.data);
            } catch (error) {
                editor.fail(error.message);
            }
        }, function () {
            editor.fail("本站分类加载失败，请检查网络后刷新页面，当前不能保存。");
        });
    }

    return {
        cloneRule: cloneRule,
        parseValues: parseValues,
        valuesFor: valuesFor,
        writeCategories: writeCategories,
        writeBookStatuses: writeBookStatuses,
        createEditor: createEditor,
        loadCategories: loadCategories
    };
}));
