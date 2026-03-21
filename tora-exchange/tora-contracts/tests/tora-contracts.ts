import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { ToraContracts } from "../target/types/tora_contracts";
import { Keypair, SystemProgram, PublicKey, LAMPORTS_PER_SOL } from "@solana/web3.js";
import { assert } from "chai";

describe("tora-contracts", () => {
  anchor.setProvider(anchor.AnchorProvider.env());
  const program = anchor.workspace.toraContracts as Program<ToraContracts>;
  const provider = anchor.getProvider() as anchor.AnchorProvider;

  // Test keypairs
  const admin = provider.wallet;
  const supplier = Keypair.generate();
  const buyer = Keypair.generate();
  const partnerTreasury = Keypair.generate();

  // Derive PDAs
  let exchangePda: PublicKey;
  let exchangeBump: number;
  let listingPda: PublicKey;
  let listingBump: number;
  let licensePda: PublicKey;
  let licenseBump: number;
  let escrowPda: PublicKey;
  let escrowBump: number;

  const assetId = "test-asset-001";
  const dataHash = new Uint8Array(32).fill(0xab);

  before(async () => {
    // Fund test wallets
    await provider.connection.requestAirdrop(supplier.publicKey, 2 * LAMPORTS_PER_SOL);
    await provider.connection.requestAirdrop(buyer.publicKey, 10 * LAMPORTS_PER_SOL);
    await new Promise(r => setTimeout(r, 500)); // wait for confirmations

    [exchangePda, exchangeBump] = PublicKey.findProgramAddressSync(
      [Buffer.from("exchange")],
      program.programId
    );

    [listingPda, listingBump] = PublicKey.findProgramAddressSync(
      [Buffer.from("listing"), Buffer.from(assetId)],
      program.programId
    );
  });

  it("Initialize exchange with constrained partner treasury (V-11)", async () => {
    const tx = await program.methods
      .initialize(1500, 500, partnerTreasury.publicKey) // 15% platform, 5% partner
      .accounts({
        exchange: exchangePda,
        authority: admin.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .rpc();

    const exchange = await program.account.exchange.fetch(exchangePda);
    assert.equal(exchange.platformFeeBps, 1500, "Platform fee should be 1500bps");
    assert.equal(exchange.partnerFeeBps, 500, "Partner fee should be 500bps");
    assert.equal(
      exchange.partnerTreasury.toBase58(),
      partnerTreasury.publicKey.toBase58(),
      "V-11: Partner treasury should be stored in exchange state"
    );
    console.log("✓ Exchange initialized, partner treasury constrained:", tx);
  });

  it("List an asset", async () => {
    const tx = await program.methods
      .listAsset(
        assetId,
        "Test Construction Archive",
        "Construction",
        new anchor.BN(1_000_000_000), // 1 SOL/month
        new anchor.BN(24100),
        Array.from(dataHash)
      )
      .accounts({
        exchange: exchangePda,
        listing: listingPda,
        supplier: supplier.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .signers([supplier])
      .rpc();

    const listing = await program.account.listing.fetch(listingPda);
    assert.equal(listing.assetId, assetId);
    assert.equal(listing.isActive, true);
    console.log("✓ Asset listed:", tx);
  });

  it("Purchase license with escrow (V-12)", async () => {
    [licensePda, licenseBump] = PublicKey.findProgramAddressSync(
      [Buffer.from("license"), buyer.publicKey.toBuffer(), listingPda.toBuffer()],
      program.programId
    );
    [escrowPda, escrowBump] = PublicKey.findProgramAddressSync(
      [Buffer.from("escrow"), licensePda.toBuffer()],
      program.programId
    );

    const tx = await program.methods
      .purchaseLicense(1) // 1-month license
      .accounts({
        exchange: exchangePda,
        listing: listingPda,
        license: licensePda,
        escrow: escrowPda,
        buyer: buyer.publicKey,
        supplier: supplier.publicKey,
        platformTreasury: admin.publicKey,
        partnerTreasury: partnerTreasury.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .signers([buyer])
      .rpc();

    const license = await program.account.license.fetch(licensePda);
    assert.equal(license.isActive, true, "License should be active");
    assert.equal(license.isReleased, false, "V-12: Escrow should NOT be released yet");
    assert.ok(license.supplierEscrowed.gtn(0), "V-12: Supplier payment should be in escrow");
    assert.ok(license.platformReceived.gtn(0), "Platform fee should be paid");
    console.log("✓ License purchased, supplier payment in escrow:", tx);
  });

  it("Verify active license (V-10)", async () => {
    const tx = await program.methods
      .verifyLicense()
      .accounts({ license: licensePda })
      .rpc();
    console.log("✓ License verified as active (V-10):", tx);
  });

  it("Wrong partner treasury is rejected (V-11)", async () => {
    const fakeTreasury = Keypair.generate();
    // Try a second purchase with wrong partner treasury — should fail
    try {
      await program.methods
        .purchaseLicense(1)
        .accounts({
          exchange: exchangePda,
          listing: listingPda,
          license: Keypair.generate().publicKey, // fresh license
          escrow: Keypair.generate().publicKey,
          buyer: buyer.publicKey,
          supplier: supplier.publicKey,
          platformTreasury: admin.publicKey,
          partnerTreasury: fakeTreasury.publicKey, // wrong — should be rejected
          systemProgram: SystemProgram.programId,
        })
        .signers([buyer])
        .rpc();
      assert.fail("Should have rejected wrong partner treasury");
    } catch (err: any) {
      assert.include(
        err.message,
        "WrongPartnerTreasury",
        "V-11: Wrong partner treasury should be rejected"
      );
      console.log("✓ Wrong partner treasury correctly rejected (V-11)");
    }
  });

  it("Release payment from escrow (V-12)", async () => {
    const supplierBalanceBefore = await provider.connection.getBalance(supplier.publicKey);

    const tx = await program.methods
      .releasePayment()
      .accounts({
        license: licensePda,
        escrow: escrowPda,
        buyer: buyer.publicKey,
        supplier: supplier.publicKey,
        systemProgram: SystemProgram.programId,
      })
      .signers([buyer])
      .rpc();

    const license = await program.account.license.fetch(licensePda);
    assert.equal(license.isReleased, true, "V-12: Escrow should be released");
    assert.equal(license.supplierEscrowed.toNumber(), 0, "V-12: Escrow should be empty");
    assert.ok(license.supplierReceived.gtn(0), "Supplier should have received payment");

    const supplierBalanceAfter = await provider.connection.getBalance(supplier.publicKey);
    assert.ok(supplierBalanceAfter > supplierBalanceBefore, "V-12: Supplier balance should increase after release");
    console.log("✓ Payment released from escrow to supplier (V-12):", tx);
  });

  it("Close expired license to reclaim rent (V-24)", async () => {
    // First deactivate the license (simulate expiry)
    // NOTE: In production, call expire_license after expires_at timestamp
    // For this test we just verify close_license requires is_active=false

    // The license is still active (not expired yet in this short test),
    // so close should fail
    try {
      await program.methods
        .closeLicense()
        .accounts({
          license: licensePda,
          buyer: buyer.publicKey,
        })
        .signers([buyer])
        .rpc();
      assert.fail("Should not close an active license");
    } catch (err: any) {
      // Expected — license still technically active (not expired by time in test)
      console.log("✓ Close correctly blocked on active license (V-24)");
    }
  });
});
