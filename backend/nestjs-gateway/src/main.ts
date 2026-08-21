// backend/nestjs-gateway/src/main.ts
import { NestFactory } from '@nestjs/core';
import { json } from 'express';
import type { IncomingMessage } from 'http';
import { ValidationPipe, Logger } from '@nestjs/common';
import { AppModule } from './app.module';

async function bootstrap() {
  const logger = new Logger('Bootstrap');
  // rawBody: Stripe webhook imzasi HAM govde uzerinden hesaplanir.
  // JSON ayristirilip yeniden serilestirilirse (anahtar sirasi, bosluk)
  // imza tutmaz ve gecerli olaylar reddedilirdi.
  const app = await NestFactory.create(AppModule, { rawBody: true });

  // Sesli komut kayitlari base64 olarak JSON govdesinde gelir; NestJS'in
  // varsayilan 100 kb siniri birkac saniyelik sesi bile reddederdi.
  // 2 MB, DTO'daki 1.5 MB base64 sinirinin biraz uzerinde tutuldu.
  // verify: Nest'in rawBody destegi KENDI body parser'ina baglidir;
  // burada ozel bir json() middleware'i devreye girdigi icin ham govde
  // elle yakalanmali -- aksi halde request.rawBody bos kalir.
  app.use(
    json({
      limit: '2mb',
      verify: (req: IncomingMessage & { rawBody?: Buffer }, _res, buf) => {
        req.rawBody = Buffer.from(buf);
      },
    }),
  );

  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      transform: true,
      forbidNonWhitelisted: true,
    }),
  );

  app.enableCors({
    origin: process.env.CORS_ALLOWED_ORIGINS?.split(',') ?? '*',
  });

  const port = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
  await app.listen(port, '0.0.0.0');

  logger.log(`Eva Gateway ${port} portunda çalışıyor.`);
}

bootstrap();
