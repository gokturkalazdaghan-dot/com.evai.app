// backend/nestjs-gateway/src/eva/eva.controller.ts
import {
  Body,
  Controller,
  Logger,
  Post,
  ServiceUnavailableException,
  UseGuards,
} from '@nestjs/common';

import { EvaChatRequestDto } from './dto/eva-chat.dto';
import { EvaService } from './eva.service';
import { DeviceAttestationGuard } from '../common/guards/device-attestation.guard';
import { RequestSignatureGuard } from '../common/guards/request-signature.guard';

export interface EvaChatResponseDto {
  reply: string;
  model: string;
}

@Controller('v1/eva')
@UseGuards(DeviceAttestationGuard, RequestSignatureGuard)
export class EvaController {
  private readonly logger = new Logger(EvaController.name);

  constructor(private readonly evaService: EvaService) {}

  @Post('chat')
  async chat(@Body() body: EvaChatRequestDto): Promise<EvaChatResponseDto> {
    try {
      return await this.evaService.chat(body.message, body.history ?? []);
    } catch (err) {
      if (err instanceof ServiceUnavailableException) {
        throw err;
      }
      this.logger.error(
        'Eva sohbeti basarisiz.',
        err instanceof Error ? err.stack : String(err),
      );
      throw new ServiceUnavailableException('Eva su anda sohbet edemiyor.');
    }
  }
}
