// backend/nestjs-gateway/src/privacy/privacy.controller.spec.ts
import { InternalServerErrorException } from '@nestjs/common';

import { PrivacyController } from './privacy.controller';
import { DataDeletionService, DeletionReport } from '../devices/data-deletion.service';

/**
 * Silme denetleyicisinin davranis sozlesmesi.
 *
 * Buradaki testler "kod calisiyor mu" degil, GUVENLIK ve DURUSTLUK
 * ozelliklerini koruyor:
 *
 *   1. Silinecek kimlik yalnizca DOGRULANMIS imzadan gelir. Govdeden
 *      okunsaydi herkes baskasinin verisini sildirebilirdi.
 *   2. Kimlik yoksa hicbir sey silinmez -- bos bir kimlikle "her seyi
 *      sil" demek felaket olurdu.
 *   3. Abonelik kaydi korunduysa bu bilgi istemciye AKTARILIR; yoksa
 *      kullaniciya "her sey silindi" yalani soylenmis olur.
 *   4. Servis patlarsa basari donmez.
 */
describe('PrivacyController', () => {
  const report = (over: Partial<DeletionReport> = {}): DeletionReport => ({
    deletedRows: { vehicle_links: 1 },
    deletedCacheKeys: 1,
    subscriptionRetained: false,
    ...over,
  });

  function build(deleteImpl: jest.Mock) {
    const service = { deleteEverythingFor: deleteImpl } as unknown as DataDeletionService;
    return new PrivacyController(service);
  }

  it('yalnizca imzasi dogrulanmis cihaz kimligini siler', async () => {
    // Arrange
    const deleteImpl = jest.fn().mockResolvedValue(report());
    const controller = build(deleteImpl);

    // Act
    await controller.deleteViaPost({ verifiedDeviceId: 'dogrulanmis-cihaz' });

    // Assert
    expect(deleteImpl).toHaveBeenCalledTimes(1);
    expect(deleteImpl).toHaveBeenCalledWith('dogrulanmis-cihaz');
  });

  it('dogrulanmis kimlik yoksa hicbir sey silmez', async () => {
    // Arrange
    const deleteImpl = jest.fn();
    const controller = build(deleteImpl);

    // Act + Assert
    await expect(controller.deleteViaPost({})).rejects.toBeInstanceOf(
      InternalServerErrorException,
    );
    expect(deleteImpl).not.toHaveBeenCalled();
  });

  it('korunan abonelik kaydini istemciye bildirir', async () => {
    // Arrange
    const deleteImpl = jest
      .fn()
      .mockResolvedValue(report({ subscriptionRetained: true }));
    const controller = build(deleteImpl);

    // Act
    const response = await controller.deleteViaPost({ verifiedDeviceId: 'abc' });

    // Assert
    expect(response.deleted).toBe(true);
    expect(response.subscriptionRetained).toBe(true);
  });

  it('silme basarisiz olursa basari donmez', async () => {
    // Arrange
    const deleteImpl = jest.fn().mockRejectedValue(new Error('veritabani yok'));
    const controller = build(deleteImpl);

    // Act + Assert
    await expect(
      controller.deleteViaPost({ verifiedDeviceId: 'abc' }),
    ).rejects.toBeInstanceOf(InternalServerErrorException);
  });

  it('DELETE ve POST ayni isi yapar', async () => {
    // Arrange
    const deleteImpl = jest.fn().mockResolvedValue(report());
    const controller = build(deleteImpl);

    // Act
    const viaPost = await controller.deleteViaPost({ verifiedDeviceId: 'abc' });
    const viaDelete = await controller.deleteViaDelete({ verifiedDeviceId: 'abc' });

    // Assert
    expect(viaDelete).toEqual(viaPost);
    expect(deleteImpl).toHaveBeenCalledTimes(2);
  });
});
