use anchor_lang::prelude::*;
use anchor_lang::system_program;

declare_id!("11111111111111111111111111111111"); // Will be replaced with real Program ID on deploy

/// TORA Exchange — Tokenized Operational Record Asset Marketplace
/// Smart contract handling:
///   1. Asset listing (suppliers register their tokenized data)
///   2. License purchase (buyers pay for access)
///   3. Revenue split (automatic distribution to supplier, platform, partner)
///   4. License tracking (on-chain record of all active licenses)

#[program]
pub mod tora_contracts {
    use super::*;

    /// Initialize the TORA Exchange configuration
    /// Called once by the platform admin (BITS)
    pub fn initialize(
        ctx: Context<Initialize>,
        platform_fee_bps: u16,     // Platform fee in basis points (e.g., 1500 = 15%)
        partner_fee_bps: u16,      // Partner fee in basis points (e.g., 500 = 5%)
    ) -> Result<()> {
        require!(platform_fee_bps + partner_fee_bps <= 5000, ToraError::FeeTooHigh); // Max 50% total fees
        
        let exchange = &mut ctx.accounts.exchange;
        exchange.authority = ctx.accounts.authority.key();
        exchange.platform_fee_bps = platform_fee_bps;
        exchange.partner_fee_bps = partner_fee_bps;
        exchange.total_listings = 0;
        exchange.total_licenses_sold = 0;
        exchange.total_volume_lamports = 0;
        exchange.bump = ctx.bumps.exchange;
        
        msg!("TORA Exchange initialized. Platform fee: {}bps, Partner fee: {}bps", 
             platform_fee_bps, partner_fee_bps);
        Ok(())
    }

    /// Register a new data asset on the exchange
    /// Called by data suppliers who want to list their tokenized operational records
    pub fn list_asset(
        ctx: Context<ListAsset>,
        asset_id: String,
        name: String,
        industry: String,
        price_lamports: u64,       // Monthly license price in lamports
        record_count: u64,         // Number of records in the corpus
        data_hash: [u8; 32],       // SHA-256 hash of the data corpus for verification
    ) -> Result<()> {
        require!(asset_id.len() <= 32, ToraError::StringTooLong);
        require!(name.len() <= 64, ToraError::StringTooLong);
        require!(industry.len() <= 32, ToraError::StringTooLong);
        require!(price_lamports > 0, ToraError::InvalidPrice);

        let listing = &mut ctx.accounts.listing;
        listing.supplier = ctx.accounts.supplier.key();
        listing.asset_id = asset_id.clone();
        listing.name = name;
        listing.industry = industry;
        listing.price_lamports = price_lamports;
        listing.record_count = record_count;
        listing.data_hash = data_hash;
        listing.is_active = true;
        listing.licenses_sold = 0;
        listing.total_revenue = 0;
        listing.created_at = Clock::get()?.unix_timestamp;
        listing.bump = ctx.bumps.listing;

        let exchange = &mut ctx.accounts.exchange;
        exchange.total_listings += 1;

        msg!("Asset listed: {} | Price: {} lamports/mo | Records: {}", 
             asset_id, price_lamports, record_count);
        Ok(())
    }

    /// Purchase a license to access a listed data asset
    /// Automatically splits payment: supplier gets majority, platform + partner get fees
    pub fn purchase_license(
        ctx: Context<PurchaseLicense>,
        license_months: u8,        // Duration of license in months (1-12)
    ) -> Result<()> {
        require!(license_months >= 1 && license_months <= 12, ToraError::InvalidDuration);
        require!(ctx.accounts.listing.is_active, ToraError::ListingInactive);

        let listing = &ctx.accounts.listing;
        let exchange = &ctx.accounts.exchange;
        
        // Calculate total payment
        let total_payment = listing.price_lamports
            .checked_mul(license_months as u64)
            .ok_or(ToraError::Overflow)?;

        // Calculate fee splits (basis points: 10000 = 100%)
        let platform_fee = total_payment
            .checked_mul(exchange.platform_fee_bps as u64)
            .ok_or(ToraError::Overflow)?
            .checked_div(10000)
            .ok_or(ToraError::Overflow)?;

        let partner_fee = total_payment
            .checked_mul(exchange.partner_fee_bps as u64)
            .ok_or(ToraError::Overflow)?
            .checked_div(10000)
            .ok_or(ToraError::Overflow)?;

        let supplier_payment = total_payment
            .checked_sub(platform_fee)
            .ok_or(ToraError::Overflow)?
            .checked_sub(partner_fee)
            .ok_or(ToraError::Overflow)?;

        // Transfer to supplier (80%)
        system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.buyer.to_account_info(),
                    to: ctx.accounts.supplier.to_account_info(),
                },
            ),
            supplier_payment,
        )?;

        // Transfer platform fee to BITS treasury (15%)
        system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.buyer.to_account_info(),
                    to: ctx.accounts.platform_treasury.to_account_info(),
                },
            ),
            platform_fee,
        )?;

        // Transfer partner fee (5%)
        if partner_fee > 0 {
            system_program::transfer(
                CpiContext::new(
                    ctx.accounts.system_program.to_account_info(),
                    system_program::Transfer {
                        from: ctx.accounts.buyer.to_account_info(),
                        to: ctx.accounts.partner_treasury.to_account_info(),
                    },
                ),
                partner_fee,
            )?;
        }

        // Create license record
        let license = &mut ctx.accounts.license;
        license.buyer = ctx.accounts.buyer.key();
        license.listing = ctx.accounts.listing.key();
        license.supplier = listing.supplier;
        license.amount_paid = total_payment;
        license.supplier_received = supplier_payment;
        license.platform_received = platform_fee;
        license.partner_received = partner_fee;
        license.months = license_months;
        license.purchased_at = Clock::get()?.unix_timestamp;
        license.expires_at = Clock::get()?.unix_timestamp + (license_months as i64 * 30 * 24 * 60 * 60);
        license.is_active = true;
        license.bump = ctx.bumps.license;

        // Update listing stats
        let listing = &mut ctx.accounts.listing;
        listing.licenses_sold += 1;
        listing.total_revenue += total_payment;

        // Update exchange stats
        let exchange = &mut ctx.accounts.exchange;
        exchange.total_licenses_sold += 1;
        exchange.total_volume_lamports += total_payment;

        msg!("License purchased! Total: {} | Supplier: {} | Platform: {} | Partner: {}", 
             total_payment, supplier_payment, platform_fee, partner_fee);
        Ok(())
    }

    /// Deactivate a listing (supplier only)
    pub fn deactivate_listing(ctx: Context<DeactivateListing>) -> Result<()> {
        let listing = &mut ctx.accounts.listing;
        listing.is_active = false;
        msg!("Listing deactivated: {}", listing.asset_id);
        Ok(())
    }

    /// Update platform fees (admin only)
    pub fn update_fees(
        ctx: Context<UpdateFees>,
        platform_fee_bps: u16,
        partner_fee_bps: u16,
    ) -> Result<()> {
        require!(platform_fee_bps + partner_fee_bps <= 5000, ToraError::FeeTooHigh);
        let exchange = &mut ctx.accounts.exchange;
        exchange.platform_fee_bps = platform_fee_bps;
        exchange.partner_fee_bps = partner_fee_bps;
        msg!("Fees updated. Platform: {}bps, Partner: {}bps", platform_fee_bps, partner_fee_bps);
        Ok(())
    }
}

// ═══════════════════════════════════════════════════════════════
// ACCOUNT STRUCTURES
// ═══════════════════════════════════════════════════════════════

#[account]
pub struct Exchange {
    pub authority: Pubkey,              // Platform admin (BITS wallet)
    pub platform_fee_bps: u16,         // Platform fee basis points
    pub partner_fee_bps: u16,          // Partner fee basis points
    pub total_listings: u64,           // Total assets listed
    pub total_licenses_sold: u64,      // Total licenses purchased
    pub total_volume_lamports: u64,    // Total transaction volume
    pub bump: u8,
}

#[account]
pub struct Listing {
    pub supplier: Pubkey,              // Data supplier wallet
    pub asset_id: String,              // Unique asset identifier (max 32 chars)
    pub name: String,                  // Asset name (max 64 chars)
    pub industry: String,              // Industry category (max 32 chars)
    pub price_lamports: u64,           // Monthly license price
    pub record_count: u64,             // Number of records in corpus
    pub data_hash: [u8; 32],           // SHA-256 hash for verification
    pub is_active: bool,               // Whether listing is active
    pub licenses_sold: u64,            // Number of licenses sold
    pub total_revenue: u64,            // Total revenue generated
    pub created_at: i64,               // Unix timestamp
    pub bump: u8,
}

#[account]
pub struct License {
    pub buyer: Pubkey,                 // Buyer wallet
    pub listing: Pubkey,               // Reference to listing
    pub supplier: Pubkey,              // Supplier wallet (for easy lookup)
    pub amount_paid: u64,              // Total amount paid
    pub supplier_received: u64,        // Amount supplier received
    pub platform_received: u64,        // Amount platform received
    pub partner_received: u64,         // Amount partner received
    pub months: u8,                    // License duration
    pub purchased_at: i64,             // Purchase timestamp
    pub expires_at: i64,               // Expiration timestamp
    pub is_active: bool,               // Whether license is active
    pub bump: u8,
}

// ═══════════════════════════════════════════════════════════════
// INSTRUCTION CONTEXTS
// ═══════════════════════════════════════════════════════════════

#[derive(Accounts)]
pub struct Initialize<'info> {
    #[account(
        init,
        payer = authority,
        space = 8 + 32 + 2 + 2 + 8 + 8 + 8 + 1,
        seeds = [b"exchange"],
        bump,
    )]
    pub exchange: Account<'info, Exchange>,
    #[account(mut)]
    pub authority: Signer<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(asset_id: String)]
pub struct ListAsset<'info> {
    #[account(
        mut,
        seeds = [b"exchange"],
        bump = exchange.bump,
    )]
    pub exchange: Account<'info, Exchange>,
    #[account(
        init,
        payer = supplier,
        space = 8 + 32 + (4 + 32) + (4 + 64) + (4 + 32) + 8 + 8 + 32 + 1 + 8 + 8 + 8 + 1,
        seeds = [b"listing", asset_id.as_bytes()],
        bump,
    )]
    pub listing: Account<'info, Listing>,
    #[account(mut)]
    pub supplier: Signer<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct PurchaseLicense<'info> {
    #[account(
        mut,
        seeds = [b"exchange"],
        bump = exchange.bump,
    )]
    pub exchange: Account<'info, Exchange>,
    #[account(
        mut,
        constraint = listing.is_active @ ToraError::ListingInactive,
    )]
    pub listing: Account<'info, Listing>,
    #[account(
        init,
        payer = buyer,
        space = 8 + 32 + 32 + 32 + 8 + 8 + 8 + 8 + 1 + 8 + 8 + 1 + 1,
        seeds = [b"license", buyer.key().as_ref(), listing.key().as_ref()],
        bump,
    )]
    pub license: Account<'info, License>,
    #[account(mut)]
    pub buyer: Signer<'info>,
    /// CHECK: Supplier wallet receives payment
    #[account(mut, constraint = supplier.key() == listing.supplier @ ToraError::WrongSupplier)]
    pub supplier: AccountInfo<'info>,
    /// CHECK: Platform treasury receives fee
    #[account(mut, constraint = platform_treasury.key() == exchange.authority @ ToraError::WrongTreasury)]
    pub platform_treasury: AccountInfo<'info>,
    /// CHECK: Partner treasury receives fee
    #[account(mut)]
    pub partner_treasury: AccountInfo<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct DeactivateListing<'info> {
    #[account(
        mut,
        constraint = listing.supplier == supplier.key() @ ToraError::Unauthorized,
    )]
    pub listing: Account<'info, Listing>,
    pub supplier: Signer<'info>,
}

#[derive(Accounts)]
pub struct UpdateFees<'info> {
    #[account(
        mut,
        seeds = [b"exchange"],
        bump = exchange.bump,
        constraint = exchange.authority == authority.key() @ ToraError::Unauthorized,
    )]
    pub exchange: Account<'info, Exchange>,
    pub authority: Signer<'info>,
}

// ═══════════════════════════════════════════════════════════════
// ERRORS
// ═══════════════════════════════════════════════════════════════

#[error_code]
pub enum ToraError {
    #[msg("Fee basis points too high (max 5000 = 50%)")]
    FeeTooHigh,
    #[msg("String exceeds maximum length")]
    StringTooLong,
    #[msg("Price must be greater than 0")]
    InvalidPrice,
    #[msg("License duration must be 1-12 months")]
    InvalidDuration,
    #[msg("Listing is not active")]
    ListingInactive,
    #[msg("Arithmetic overflow")]
    Overflow,
    #[msg("Unauthorized")]
    Unauthorized,
    #[msg("Wrong supplier account")]
    WrongSupplier,
    #[msg("Wrong treasury account")]
    WrongTreasury,
}
