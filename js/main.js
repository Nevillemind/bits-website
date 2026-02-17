document.addEventListener('DOMContentLoaded', () => {
  // ============================================
  // HAMBURGER MENU TOGGLE
  // ============================================
  const hamburger = document.getElementById('hamburger');
  const navLinks = document.getElementById('nav-links');

  hamburger.addEventListener('click', () => {
    const isOpen = navLinks.classList.toggle('open');
    hamburger.setAttribute('aria-expanded', isOpen);
    hamburger.classList.toggle('active', isOpen);
  });

  // Close menu on link click (mobile)
  navLinks.querySelectorAll('.nav-link').forEach(link => {
    link.addEventListener('click', () => {
      navLinks.classList.remove('open');
      hamburger.classList.remove('active');
      hamburger.setAttribute('aria-expanded', 'false');
    });
  });

  // ============================================
  // SMOOTH SCROLL FOR ANCHOR LINKS
  // ============================================
  document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function(e) {
      e.preventDefault();
      const targetId = this.getAttribute('href');
      if (targetId === '#') return;
      
      const target = document.querySelector(targetId);
      if (target) {
        const navHeight = document.querySelector('.navbar').offsetHeight;
        const targetPosition = target.getBoundingClientRect().top + window.pageYOffset - navHeight;
        
        window.scrollTo({
          top: targetPosition,
          behavior: 'smooth'
        });
      }
    });
  });

  // ============================================
  // SCROLL ANIMATIONS - FADE IN ON VIEW
  // ============================================
  const animateOnScroll = () => {
    const elements = document.querySelectorAll(
      '.product-card, .principle-card, .philosophy-header, .philosophy-quote, section h2, .hero-content > *'
    );
    
    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('fade-in-visible');
          // Once animated, stop observing
          observer.unobserve(entry.target);
        }
      });
    }, {
      threshold: 0.15,
      rootMargin: '0px 0px -50px 0px'
    });

    elements.forEach((el, index) => {
      // Add stagger class for sequential animations
      el.classList.add('fade-in-element');
      el.style.transitionDelay = `${(index % 4) * 0.1}s`;
      observer.observe(el);
    });
  };

  animateOnScroll();

  // ============================================
  // ACTIVE NAV LINK HIGHLIGHTING
  // ============================================
  const sections = document.querySelectorAll('section[id]');
  const navLinkEls = document.querySelectorAll('.nav-link');

  const activeNavObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const id = entry.target.id;
        navLinkEls.forEach(link => {
          link.classList.toggle('active', link.getAttribute('href') === '#' + id);
        });
      }
    });
  }, { threshold: 0.3, rootMargin: '-80px 0px -50% 0px' });

  sections.forEach(section => activeNavObserver.observe(section));

  // ============================================
  // NAVBAR BACKGROUND ON SCROLL
  // ============================================
  const navbar = document.querySelector('.navbar');
  let lastScroll = 0;

  const handleNavbarScroll = () => {
    const currentScroll = window.pageYOffset;
    
    if (currentScroll > 50) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
    
    lastScroll = currentScroll;
  };

  window.addEventListener('scroll', handleNavbarScroll, { passive: true });
  handleNavbarScroll(); // Initial check

  // ============================================
  // SUBTLE PARALLAX ON HERO
  // ============================================
  const heroContent = document.querySelector('.hero-content');
  const heroBg = document.querySelector('.hero-bg');
  
  if (heroContent && heroBg) {
    let ticking = false;
    
    const updateParallax = () => {
      const scrolled = window.pageYOffset;
      const heroHeight = document.querySelector('.hero').offsetHeight;
      
      // Only apply parallax while hero is visible
      if (scrolled < heroHeight) {
        const parallaxAmount = scrolled * 0.3;
        heroContent.style.transform = `translateY(${parallaxAmount}px)`;
        heroContent.style.opacity = 1 - (scrolled / heroHeight) * 0.5;
      }
      
      ticking = false;
    };
    
    window.addEventListener('scroll', () => {
      if (!ticking) {
        requestAnimationFrame(updateParallax);
        ticking = true;
      }
    }, { passive: true });
  }

  // ============================================
  // BUTTON RIPPLE EFFECT
  // ============================================
  const addRippleEffect = (buttons) => {
    buttons.forEach(button => {
      button.addEventListener('click', function(e) {
        const rect = this.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        
        const ripple = document.createElement('span');
        ripple.classList.add('ripple');
        ripple.style.left = `${x}px`;
        ripple.style.top = `${y}px`;
        
        this.appendChild(ripple);
        
        setTimeout(() => ripple.remove(), 600);
      });
    });
  };

  addRippleEffect(document.querySelectorAll('.cta-button, .product-cta'));

  // ============================================
  // PRODUCT CARD TILT EFFECT
  // ============================================
  const productCards = document.querySelectorAll('.product-card');
  
  productCards.forEach(card => {
    card.addEventListener('mousemove', function(e) {
      const rect = this.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      
      const centerX = rect.width / 2;
      const centerY = rect.height / 2;
      
      const rotateX = (y - centerY) / 20;
      const rotateY = (centerX - x) / 20;
      
      this.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) translateY(-8px)`;
    });
    
    card.addEventListener('mouseleave', function() {
      this.style.transform = '';
    });
  });

  // ============================================
  // TYPING ANIMATION FOR HERO TAGLINE (optional)
  // ============================================
  const typeWriter = (element, text, speed = 50) => {
    let i = 0;
    element.textContent = '';
    
    const type = () => {
      if (i < text.length) {
        element.textContent += text.charAt(i);
        i++;
        setTimeout(type, speed);
      }
    };
    
    type();
  };

  // Uncomment below to enable typing animation
  // const heroTagline = document.querySelector('.hero-tagline');
  // if (heroTagline) {
  //   const originalText = heroTagline.textContent;
  //   setTimeout(() => typeWriter(heroTagline, originalText, 40), 500);
  // }

  // ============================================
  // SCROLL PROGRESS INDICATOR
  // ============================================
  const createScrollProgress = () => {
    const progressBar = document.createElement('div');
    progressBar.classList.add('scroll-progress');
    document.body.appendChild(progressBar);

    const updateProgress = () => {
      const scrollTop = window.pageYOffset;
      const docHeight = document.documentElement.scrollHeight - window.innerHeight;
      const scrollPercent = (scrollTop / docHeight) * 100;
      progressBar.style.width = `${scrollPercent}%`;
    };

    window.addEventListener('scroll', updateProgress, { passive: true });
    updateProgress();
  };

  createScrollProgress();

  // ============================================
  // LAZY LOAD IMAGES (if needed)
  // ============================================
  if ('IntersectionObserver' in window) {
    const lazyImages = document.querySelectorAll('img[data-src]');
    
    const imageObserver = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const img = entry.target;
          img.src = img.dataset.src;
          img.removeAttribute('data-src');
          img.classList.add('loaded');
          imageObserver.unobserve(img);
        }
      });
    }, { rootMargin: '50px' });

    lazyImages.forEach(img => imageObserver.observe(img));
  }

  // ============================================
  // ACCESSIBILITY: Reduce motion for users who prefer it
  // ============================================
  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  
  if (prefersReducedMotion.matches) {
    document.documentElement.style.setProperty('--animation-duration', '0.01ms');
    // Disable parallax
    if (heroContent) {
      heroContent.style.transform = 'none';
    }
  }

  // ============================================
  // FOCUS VISIBLE IMPROVEMENTS
  // ============================================
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') {
      document.body.classList.add('keyboard-navigation');
    }
  });

  document.addEventListener('mousedown', () => {
    document.body.classList.remove('keyboard-navigation');
  });

  console.log('✨ BITS website animations loaded successfully');
});
