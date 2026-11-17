# Only this reviewed mysqldump format is accepted. Never echo rejected values.
function reject() {
    printf "migration: rejected SQL in %s at line %d\n", table, NR > "/dev/stderr"
    exit 1
}
BEGIN {
    columns["book_category"] = "`id`, `work_direction`, `name`, `sort`, `create_user_id`, `create_time`, `update_user_id`, `update_time`"
    columns["book"] = "`id`, `work_direction`, `cat_id`, `cat_name`, `pic_url`, `book_name`, `author_id`, `author_name`, `book_desc`, `score`, `book_status`, `visit_count`, `word_count`, `comment_count`, `yesterday_buy`, `last_index_id`, `last_index_name`, `last_index_update_time`, `is_vip`, `status`, `update_time`, `create_time`, `crawl_source_id`, `crawl_book_id`, `crawl_last_time`, `crawl_is_stop`"
    columns["book_index"] = "`id`, `book_id`, `index_num`, `index_name`, `source_index_id`, `word_count`, `is_vip`, `book_price`, `storage_type`, `create_time`, `update_time`"
    columns["book_content"] = "`id`, `index_id`, `content`"
    for (i = 0; i < 10; i++) columns["book_content" i] = columns["book_content"]
    columns["book_setting"] = "`id`, `book_id`, `sort`, `type`, `create_time`, `create_user_id`, `update_time`, `update_user_id`"
    columns["crawl_source"] = "`id`, `source_name`, `crawl_rule`, `source_status`, `create_time`, `update_time`"
    columns["crawl_batch_task"] = "`id`, `source_id`, `crawl_count_success`, `crawl_count_target`, `task_status`, `start_time`, `end_time`"
    columns["crawl_single_task"] = "`id`, `source_id`, `source_name`, `source_book_id`, `cat_id`, `book_name`, `author_name`, `task_status`, `exc_count`, `create_time`"
    if (!(table in columns)) reject()
    prefix = "INSERT INTO `" table "` (" columns[table] ") VALUES ("
    fixed["/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;"] = 1
    fixed["/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;"] = 1
    fixed["/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;"] = 1
    fixed["/*!50503 SET NAMES utf8mb4 */;"] = 1
    fixed["/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;"] = 1
    fixed["/*!40103 SET TIME_ZONE='+00:00' */;"] = 1
    fixed["/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;"] = 1
    fixed["/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;"] = 1
    fixed["/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;"] = 1
    fixed["/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;"] = 1
    fixed["/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;"] = 1
    fixed["/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;"] = 1
    fixed["/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;"] = 1
    fixed["/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;"] = 1
    fixed["/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;"] = 1
    fixed["/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;"] = 1
    fixed["/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;"] = 1
    fixed["/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;"] = 1
    # POSIX regex runs in C, once per row; there is no per-byte awk loop.
    number = "(NULL|[-+]?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?|0x[0-9A-Fa-f]+)"
    values = "^" number "(," number ")*[)];$"
}
{
    sub(/\r$/, "")
    if ($0 == "" || $0 in fixed) next
    if (index($0, prefix) != 1 || substr($0, length($0) - 1) != ");") reject()
    # Enabled only by import.sh for a privately reviewed and sealed export.
    # Public tools default to validating the complete literal grammar below.
    if (trusted_dump == 1) {
        if (emit == 1) print $0
        next
    }
    body = substr($0, length(prefix) + 1)
    # Quoted text may legitimately contain SQL-like text, semicolons or escapes.
    # Replace only complete mysqldump string literals before validating tokens.
    gsub(/'([^'\\]|\\.|'')*'/, "0", body)
    if (body !~ values) reject()
    if (emit == 1) print $0
}
