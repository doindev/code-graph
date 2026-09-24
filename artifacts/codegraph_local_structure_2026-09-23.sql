-- Recreate codegraph_local (structure only)
-- Source: MySQL Local Docker, MySQL 8.4.11; obtained only through code-graph MCP.
-- Captured: 2026-09-24T03:06:11.756Z
-- Run against the destination server, where codegraph_local does not already exist.
-- Intended for the mysql client or an SQL editor supporting DELIMITER directives.
-- Includes 2 tables, 2 views, 1 function and 1 procedure. No rows are copied.
-- No triggers or scheduled events were returned by the source metadata.
-- No users/grants are copied. Source DEFINER=`codegraph`@`%` clauses are omitted:
-- the executing account becomes the definer; SQL SECURITY settings are preserved.
-- CREATE DATABASE intentionally fails if this database already exists.
-- Run without a continue-on-error/--force option. No DROP statements are included.

CREATE DATABASE `codegraph_local` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `codegraph_local`;

SET @CG_OLD_SQL_MODE = @@SESSION.sql_mode;
SET @CG_OLD_CHARACTER_SET_CLIENT = @@SESSION.character_set_client;
SET @CG_OLD_CHARACTER_SET_RESULTS = @@SESSION.character_set_results;
SET @CG_OLD_COLLATION_CONNECTION = @@SESSION.collation_connection;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- Tables (including primary keys and secondary indexes).
CREATE TABLE `mcp_test_customers_20260917` (
  `customer_id` int NOT NULL,
  `customer_name` varchar(80) NOT NULL,
  `region` varchar(30) NOT NULL,
  PRIMARY KEY (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `mcp_test_orders_20260917` (
  `order_id` int NOT NULL,
  `customer_id` int NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `status` varchar(16) NOT NULL,
  `ordered_on` date NOT NULL,
  PRIMARY KEY (`order_id`),
  KEY `ix_mcp_orders_customer_status` (`customer_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Stored routines; the captured routine SQL mode is set above.
DELIMITER $$
CREATE FUNCTION `mcp_test_add_tax_20260917`(p_amount DECIMAL(10,2), p_tax_rate DECIMAL(5,4)) RETURNS decimal(12,2)
    NO SQL
    DETERMINISTIC
    SQL SECURITY INVOKER
RETURN ROUND(p_amount * (1 + p_tax_rate), 2)$$

CREATE PROCEDURE `mcp_test_customer_orders_20260917`(IN p_customer_id INT)
    READS SQL DATA
    SQL SECURITY INVOKER
BEGIN
  SELECT customer_id, customer_name, region
  FROM codegraph_local.mcp_test_customers_20260917
  WHERE customer_id = p_customer_id;
  SELECT order_id, amount, status
  FROM codegraph_local.mcp_test_orders_20260917
  WHERE customer_id = p_customer_id
  ORDER BY order_id;
END$$

DELIMITER ;

-- Views. The captured ALGORITHM and SQL SECURITY values are preserved.
CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `mcp_test_customer_totals_20260917` AS select `c`.`customer_id` AS `customer_id`,`c`.`customer_name` AS `customer_name`,count(`o`.`order_id`) AS `order_count`,coalesce(sum((case when (`o`.`status` = 'paid') then `o`.`amount` else 0 end)),0) AS `paid_total` from (`mcp_test_customers_20260917` `c` left join `mcp_test_orders_20260917` `o` on((`o`.`customer_id` = `c`.`customer_id`))) group by `c`.`customer_id`,`c`.`customer_name`;

CREATE ALGORITHM=UNDEFINED SQL SECURITY DEFINER VIEW `mcp_test_order_details_20260917` AS select `o`.`order_id` AS `order_id`,`c`.`customer_name` AS `customer_name`,`c`.`region` AS `region`,`o`.`amount` AS `amount`,`o`.`status` AS `status`,`o`.`ordered_on` AS `ordered_on` from (`mcp_test_orders_20260917` `o` join `mcp_test_customers_20260917` `c` on((`c`.`customer_id` = `o`.`customer_id`)));

-- Restore the caller's session settings.
SET SESSION sql_mode = @CG_OLD_SQL_MODE;
SET SESSION character_set_client = @CG_OLD_CHARACTER_SET_CLIENT;
SET SESSION character_set_results = @CG_OLD_CHARACTER_SET_RESULTS;
SET SESSION collation_connection = @CG_OLD_COLLATION_CONNECTION;
