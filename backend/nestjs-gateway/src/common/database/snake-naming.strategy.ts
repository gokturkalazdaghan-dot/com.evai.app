// backend/nestjs-gateway/src/common/database/snake-naming.strategy.ts
import { DefaultNamingStrategy, NamingStrategyInterface } from 'typeorm';
import { snakeCase } from 'typeorm/util/StringUtils';

/**
 * Kanonik şema `database/schema.sql` dosyasıdır ve baştan sona snake_case
 * kolon adları kullanır (station_id, max_power_kw, country_code ...).
 * Entity sınıfları ise TypeScript alışkanlığı gereği camelCase property
 * adları taşır ve hiçbiri açık `name:` vermez.
 *
 * TypeORM'un varsayılan naming stratejisi property adını olduğu gibi kolon
 * adı olarak kullandığından, bu iki dünya birbirine bağlanmazsa üretilen
 * SQL var olmayan "stationId" / "maxPowerKw" kolonlarını arar.
 *
 * Bu strateji tek noktadan camelCase -> snake_case dönüşümü yaparak entity
 * modelini mevcut şemaya hizalar. `@Entity('...')` ve `@JoinColumn({ name })`
 * gibi AÇIKÇA verilmiş adlar korunur.
 */
export class SnakeNamingStrategy extends DefaultNamingStrategy implements NamingStrategyInterface {
  tableName(className: string, customName?: string): string {
    return customName || snakeCase(className);
  }

  columnName(propertyName: string, customName: string, embeddedPrefixes: string[]): string {
    return snakeCase(embeddedPrefixes.concat(customName || propertyName).join('_'));
  }

  relationName(propertyName: string): string {
    return snakeCase(propertyName);
  }

  joinColumnName(relationName: string, referencedColumnName: string): string {
    return snakeCase(`${relationName}_${referencedColumnName}`);
  }

  joinTableName(
    firstTableName: string,
    secondTableName: string,
    firstPropertyName: string,
  ): string {
    return snakeCase(
      `${firstTableName}_${firstPropertyName.replace(/\./gi, '_')}_${secondTableName}`,
    );
  }

  joinTableColumnName(tableName: string, propertyName: string, columnName?: string): string {
    return snakeCase(`${tableName}_${columnName || propertyName}`);
  }

  classTableInheritanceParentColumnName(
    parentTableName: string,
    parentTableIdPropertyName: string,
  ): string {
    return snakeCase(`${parentTableName}_${parentTableIdPropertyName}`);
  }
}
