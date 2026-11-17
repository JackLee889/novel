function fillPiaotiaTemplate() {
    fillCrawlTemplate("piaotia", "飘天文学网", "16/16238");
}

function fillBqg616Template() {
    fillCrawlTemplate("bqg616", "笔趣阁616", "2530");
}

function fillHetushuTemplate() {
    fillCrawlTemplate("hetushu", "和图书", "4");
}

function fillUukanshuTemplate() {
    fillCrawlTemplate("uukanshu", "UU看书", "10980");
}

function crawlTemplateHint(template) {
    return template === "uukanshu"
        ? " UU看书的分类ID 0为全部书库入口，按图书详情自动归类；分类缺失的书归入其他小说。"
        : "";
}

function fillCrawlTemplate(template, sourceName, exampleBookId) {
    var buttons = $("#piaotiaTemplate, #bqg616Template, #hetushuTemplate, #uukanshuTemplate");
    buttons.prop("disabled", true);
    $("#templateStatus").text("正在读取" + sourceName + "模板…");
    $.getJSON("/crawl-templates/" + template + ".json")
        .done(function (rule) {
            $.each(rule, function (field, value) {
                if (typeof value === "string") {
                    $("#" + field).val(value);
                }
            });
            for (var i = 1; i <= 7; i++) {
                $("#catId" + i).val(rule.catIdRule["catId" + i] || "");
            }
            $("#bookStatus0, #bookStatus1").val("");
            $.each(rule.bookStatusRule, function (label, status) {
                $("#bookStatus" + status).val(label);
            });
            $("#sourceName").val(sourceName);
            $("#templateStatus").text("已填写" + sourceName + "模板，可检查后提交。单本采集的源站小说ID示例：" + exampleBookId + "。");
        })
        .fail(function () {
            $("#templateStatus").text(sourceName + "模板读取失败，请刷新页面重试。");
        })
        .always(function () {
            buttons.prop("disabled", false);
        });
}
