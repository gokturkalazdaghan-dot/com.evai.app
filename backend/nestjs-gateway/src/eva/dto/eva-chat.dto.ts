// backend/nestjs-gateway/src/eva/dto/eva-chat.dto.ts
import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  IsArray,
  IsIn,
  IsNotEmpty,
  IsOptional,
  IsString,
  MaxLength,
  ValidateNested,
} from 'class-validator';

export class EvaChatTurnDto {
  @IsIn(['user', 'assistant'], { message: 'Gecersiz sohbet rolu.' })
  role!: 'user' | 'assistant';

  @IsString()
  @IsNotEmpty({ message: 'Sohbet mesaji bos olamaz.' })
  @MaxLength(2000)
  content!: string;
}

/**
 * Eva yol asistanina giden sohbet istegi.
 *
 * Gecmisi istemci gonderir: sunucu oturum tutmaz. Bu, cihaz degistirmede
 * ve sunucu olceklemede tek kaynakli bir bellek yuku biriktirmez. Ust
 * sinir, xAI'ye her turda butun gecmisi faturalamamamiz icindir.
 */
export class EvaChatRequestDto {
  @IsString()
  @IsNotEmpty({ message: 'Mesaj gerekli.' })
  @MaxLength(2000)
  message!: string;

  @IsOptional()
  @IsArray()
  @ArrayMaxSize(20)
  @ValidateNested({ each: true })
  @Type(() => EvaChatTurnDto)
  history?: EvaChatTurnDto[];
}
